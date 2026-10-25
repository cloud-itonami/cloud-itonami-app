import assert from "node:assert/strict";
import { createPrivateKey, createPublicKey, sign } from "node:crypto";
import test from "node:test";
import {
  checkpointMessage,
  validPeerId,
  verifyCheckpoint,
} from "../src/cloud/itonami/app/awai_registry_checkpoint.mjs";

const alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
const pkcs8Ed25519Prefix = Buffer.from("302e020100300506032b657004220420", "hex");

function encodeBase58(bytes) {
  let value = BigInt(`0x${bytes.toString("hex")}`);
  let result = "";
  while (value > 0n) {
    const digit = Number(value % 58n);
    result = alphabet[digit] + result;
    value /= 58n;
  }
  const zeroBytes = bytes.findIndex((byte) => byte !== 0);
  const leadingZeros = zeroBytes === -1 ? bytes.length : zeroBytes;
  return `${"1".repeat(leadingZeros)}${result}`;
}

function fixture(seedByte, issuedAt = 1_800_000_000) {
  const privateKey = createPrivateKey({
    key: Buffer.concat([pkcs8Ed25519Prefix, Buffer.alloc(32, seedByte)]),
    format: "der",
    type: "pkcs8",
  });
  const publicDer = createPublicKey(privateKey).export({ format: "der", type: "spki" });
  const rawPublicKey = publicDer.subarray(publicDer.length - 32);
  const protobufPublicKey = Buffer.concat([Buffer.from([8, 1, 18, 32]), rawPublicKey]);
  const multihash = Buffer.concat([Buffer.from([0, protobufPublicKey.length]), protobufPublicKey]);
  const peerId = encodeBase58(multihash);
  const dnaCid = "bafkreifwintjn6lddprilx7xpbxrtorzkuxhjwhs7c4x3c4aippelcipnu";
  const recordCids = [];
  const signature = sign(null,
    Buffer.from(checkpointMessage(dnaCid, peerId, issuedAt, recordCids), "utf8"),
    privateKey);
  return {
    peerId,
    snapshot: {
      schema: "awai.app-registry.read.v4",
      "dna-cid": dnaCid,
      records: [],
      checkpoint: {
        schema: "awai.app-registry.checkpoint.v1",
        "peer-id": peerId,
        "issued-at": issuedAt,
        "record-cids": recordCids,
        "signature-hex": signature.toString("hex"),
      },
    },
  };
}

test("accepts a fresh checkpoint signed by the peer ID's inline Ed25519 key", () => {
  const signed = fixture(7);
  assert.match(signed.peerId, /^12D3Koo/);
  assert.equal(validPeerId(signed.peerId), true);
  assert.equal(verifyCheckpoint(signed.peerId, signed.snapshot, 1_800_000_000), true);
});

test("rejects changed records, a different peer key, and stale checkpoints", () => {
  const signed = fixture(9);
  const other = fixture(10);
  const changed = structuredClone(signed.snapshot);
  changed.records = [{ cid: "bafkreiajuvwx5c52tzkfugs6zzye45sunx4ivoioeht5ckn7o5ak3u3ynq" }];
  assert.equal(verifyCheckpoint(signed.peerId, changed, 1_800_000_000), false);
  assert.equal(verifyCheckpoint(other.peerId, signed.snapshot, 1_800_000_000), false);
  assert.equal(verifyCheckpoint(signed.peerId, signed.snapshot, 1_800_000_121), false);
  assert.equal(verifyCheckpoint(signed.peerId, signed.snapshot, 1_799_999_969), false);
  assert.equal(validPeerId("not-a-peer-id"), false);
});
