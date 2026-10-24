import { createPublicKey, verify } from "node:crypto";

const alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
const x509Ed25519Prefix = Buffer.from("302a300506032b6570032100", "hex");

function decodeBase58(value) {
  if (typeof value !== "string" || value.length < 1 || value.length > 128) return null;
  let number = 0n;
  for (const character of value) {
    const digit = alphabet.indexOf(character);
    if (digit < 0) return null;
    number = number * 58n + BigInt(digit);
  }

  const digits = [];
  while (number > 0n) {
    digits.unshift(Number(number & 0xffn));
    number >>= 8n;
  }
  const leadingOnes = value.match(/^1*/)?.[0].length ?? 0;
  return Buffer.concat([Buffer.alloc(leadingOnes), Buffer.from(digits)]);
}

function peerPublicKey(peerId) {
  const multihash = decodeBase58(peerId);
  if (!multihash
    || multihash.length !== 38
    || !multihash.subarray(0, 6).equals(Buffer.from([0, 36, 8, 1, 18, 32]))) {
    return null;
  }

  try {
    return createPublicKey({
      key: Buffer.concat([x509Ed25519Prefix, multihash.subarray(6)]),
      format: "der",
      type: "spki",
    });
  } catch {
    return null;
  }
}

export function validPeerId(peerId) {
  return peerPublicKey(peerId) !== null;
}

export function checkpointMessage(dnaCid, peerId, issuedAt, recordCids) {
  return `awai-app-registry-checkpoint.v1\n${dnaCid}\n${peerId}\n${issuedAt}\n${recordCids.join("\n")}`;
}

export function verifyCheckpoint(peerId, snapshot, now) {
  try {
    const checkpoint = snapshot?.checkpoint;
    const recordCids = snapshot?.records?.map((record) => record.cid);
    const key = peerPublicKey(peerId);
    if (!key
      || checkpoint?.schema !== "awai.app-registry.checkpoint.v1"
      || checkpoint["peer-id"] !== peerId
      || !Number.isSafeInteger(checkpoint["issued-at"])
      || checkpoint["issued-at"] < now - 120
      || checkpoint["issued-at"] > now + 30
      || !Array.isArray(recordCids)
      || JSON.stringify(checkpoint["record-cids"]) !== JSON.stringify(recordCids)
      || new Set(recordCids).size !== recordCids.length
      || typeof checkpoint["signature-hex"] !== "string"
      || !/^[0-9a-f]{128}$/.test(checkpoint["signature-hex"])) {
      return false;
    }

    return verify(
      null,
      Buffer.from(checkpointMessage(snapshot["dna-cid"], peerId,
        checkpoint["issued-at"], recordCids), "utf8"),
      key,
      Buffer.from(checkpoint["signature-hex"], "hex"),
    );
  } catch {
    return false;
  }
}
