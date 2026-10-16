#include <ldns/dname.h>
#include <ldns/error.h>
#include <ldns/packet.h>
#include <ldns/rdata.h>
#include <ldns/rr.h>
#include <ldns/wire2host.h>
#include <unbound.h>

#include <ctype.h>
#include <inttypes.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <time.h>

static void emit_status(const char *status, const char *reason) {
  printf("{\"status\":\"%s\",\"reason\":\"%s\"}\n", status,
         reason);
}

static void emit_json_string(const char *value) {
  putchar('"');
  for (const unsigned char *p = (const unsigned char *)value; *p != '\0'; ++p) {
    if (*p == '"' || *p == '\\') {
      putchar('\\');
      putchar((int)*p);
    } else if (*p < 0x20) {
      printf("\\u%04x", (unsigned int)*p);
    } else {
      putchar((int)*p);
    }
  }
  putchar('"');
}

static int valid_qname(const char *name) {
  size_t length = strlen(name);
  size_t label_length = 0;
  int labels = 0;
  int previous_hyphen = 0;

  if (length < 3 || length > 254 || name[length - 1] != '.') {
    return 0;
  }

  for (size_t i = 0; i < length; ++i) {
    unsigned char c = (unsigned char)name[i];
    if (c == '.') {
      if (label_length == 0 || label_length > 63 || previous_hyphen) {
        return 0;
      }
      ++labels;
      label_length = 0;
      previous_hyphen = 0;
      continue;
    }
    if (!(islower(c) || isdigit(c) || c == '-' || c == '_')) {
      return 0;
    }
    if (label_length == 0 && c == '-') {
      return 0;
    }
    previous_hyphen = c == '-';
    ++label_length;
  }

  return labels >= 2 && label_length == 0;
}

static int flatten_txt_rdata(const uint8_t *wire, size_t wire_length,
                              char *output, size_t output_capacity) {
  size_t offset = 0;
  size_t used = 0;

  while (offset < wire_length) {
    size_t chunk_length = wire[offset++];
    if (offset + chunk_length > wire_length ||
        used + chunk_length >= output_capacity) {
      return 0;
    }
    for (size_t i = 0; i < chunk_length; ++i) {
      unsigned char c = wire[offset + i];
      if (c < 0x20 || c > 0x7e) {
        return 0;
      }
      output[used++] = (char)c;
    }
    offset += chunk_length;
  }

  output[used] = '\0';
  return used > 0;
}

static int exact_answer(const struct ub_result *result, const char *qname,
                        char *binding_value, size_t value_capacity,
                        time_t *signature_expiry) {
  ldns_pkt *packet = NULL;
  ldns_rdf *expected_name = NULL;
  int valid = 0;
  size_t txt_count = 0;
  size_t signature_count = 0;
  time_t earliest_expiry = (time_t)LLONG_MAX;
  char packet_txt[1024];

  if (ldns_wire2pkt(&packet, (const uint8_t *)result->answer_packet,
                    (size_t)result->answer_len) != LDNS_STATUS_OK) {
    goto cleanup;
  }

  expected_name = ldns_dname_new_frm_str(qname);
  if (expected_name == NULL || ldns_pkt_get_rcode(packet) != LDNS_RCODE_NOERROR ||
      ldns_pkt_qdcount(packet) != 1 ||
      ldns_pkt_ancount(packet) !=
          ldns_rr_list_rr_count(ldns_pkt_answer(packet))) {
    goto cleanup;
  }

  ldns_rr_list *questions = ldns_pkt_question(packet);
  ldns_rr *question = ldns_rr_list_rr(questions, 0);
  if (ldns_rr_get_type(question) != LDNS_RR_TYPE_TXT ||
      ldns_rr_get_class(question) != LDNS_RR_CLASS_IN ||
      ldns_dname_compare(ldns_rr_owner(question), expected_name) != 0) {
    goto cleanup;
  }

  ldns_rr_list *answers = ldns_pkt_answer(packet);
  for (size_t i = 0; i < ldns_rr_list_rr_count(answers); ++i) {
    ldns_rr *record = ldns_rr_list_rr(answers, i);
    ldns_rr_type type = ldns_rr_get_type(record);
    if (ldns_rr_get_class(record) != LDNS_RR_CLASS_IN ||
        ldns_dname_compare(ldns_rr_owner(record), expected_name) != 0) {
      goto cleanup;
    }

    if (type == LDNS_RR_TYPE_TXT) {
      if (++txt_count != 1 || result->data == NULL ||
          result->data[0] == NULL || result->data[1] != NULL ||
          result->len == NULL || result->len[0] <= 0 ||
          !flatten_txt_rdata((const uint8_t *)result->data[0],
                             (size_t)result->len[0], packet_txt,
                             sizeof(packet_txt))) {
        goto cleanup;
      }
      continue;
    }

    if (type == LDNS_RR_TYPE_RRSIG) {
      ldns_rdf *covered = ldns_rr_rdf(record, 0);
      ldns_rdf *expiration = ldns_rr_rdf(record, 4);
      if (covered == NULL || expiration == NULL ||
          ldns_rdf2native_int16(covered) != LDNS_RR_TYPE_TXT) {
        goto cleanup;
      }
      time_t expires_at = ldns_rdf2native_time_t(expiration);
      if (expires_at < earliest_expiry) {
        earliest_expiry = expires_at;
      }
      ++signature_count;
      continue;
    }

    /* CNAME, DNAME, extra RRsets, and unknown records fail closed. */
    goto cleanup;
  }

  if (txt_count != 1 || signature_count == 0 || strlen(packet_txt) == 0 ||
      strlen(packet_txt) >= value_capacity) {
    goto cleanup;
  }

  memcpy(binding_value, packet_txt, strlen(packet_txt) + 1);
  *signature_expiry = earliest_expiry;
  valid = 1;

cleanup:
  if (expected_name != NULL) {
    ldns_rdf_deep_free(expected_name);
  }
  if (packet != NULL) {
    ldns_pkt_free(packet);
  }
  return valid;
}

int main(int argc, char **argv) {
  struct ub_ctx *context = NULL;
  struct ub_result *result = NULL;
  const char *qname;
  const char *anchor_file;
  char binding_value[1024];
  time_t signature_expiry = 0;
  time_t checked_at;
  int status;

  if (argc != 3 || !valid_qname(argv[1])) {
    emit_status("refuse", "invalid-query-name");
    return 0;
  }
  qname = argv[1];
  anchor_file = argv[2];

  context = ub_ctx_create();
  if (context == NULL ||
      ub_ctx_resolvconf(context, "/etc/resolv.conf") != 0 ||
      ub_ctx_add_ta_file(context, anchor_file) != 0) {
    emit_status("unavailable", "dnssec-validator-unavailable");
    if (context != NULL) {
      ub_ctx_delete(context);
    }
    return 0;
  }

  status = ub_resolve(context, qname, LDNS_RR_TYPE_TXT, LDNS_RR_CLASS_IN,
                      &result);
  if (status != 0 || result == NULL) {
    emit_status("unavailable", "dns-resolution-failed");
    ub_ctx_delete(context);
    return 0;
  }

  checked_at = time(NULL);
  if (result->bogus) {
    emit_status("refuse", "dnssec-bogus");
    goto cleanup;
  }
  if (!result->secure) {
    emit_status("refuse", "dnssec-not-secure");
    goto cleanup;
  }
  if (!result->havedata || result->nxdomain || result->rcode != LDNS_RCODE_NOERROR) {
    emit_status("unavailable", "dns-record-not-found");
    goto cleanup;
  }
  if (result->qname == NULL || strcasecmp(result->qname, qname) != 0 ||
      (result->canonname != NULL &&
       strcasecmp(result->canonname, qname) != 0)) {
    emit_status("refuse", "dns-name-alias-or-mismatch");
    goto cleanup;
  }
  if (result->ttl <= 0 || !exact_answer(result, qname, binding_value,
                                        sizeof(binding_value),
                                        &signature_expiry)) {
    emit_status("refuse", "dns-answer-not-exact");
    goto cleanup;
  }
  if (signature_expiry <= checked_at) {
    emit_status("refuse", "dns-signature-expired");
    goto cleanup;
  }

  int ttl = result->ttl;
  if (ttl > 300) {
    ttl = 300;
  }
  if ((time_t)ttl > signature_expiry - checked_at) {
    ttl = (int)(signature_expiry - checked_at);
  }
  if (ttl <= 0) {
    emit_status("refuse", "dns-proof-expired");
    goto cleanup;
  }

  printf("{\"status\":\"verified\",\"qname\":");
  emit_json_string(qname);
  printf(",\"txtValue\":");
  emit_json_string(binding_value);
  printf(",\"checkedAt\":%" PRIdMAX ",\"ttlSeconds\":%d,"
         "\"signatureExpiresAt\":%" PRIdMAX "}\n",
         (intmax_t)checked_at, ttl, (intmax_t)signature_expiry);

cleanup:
  ub_resolve_free(result);
  ub_ctx_delete(context);
  return 0;
}
