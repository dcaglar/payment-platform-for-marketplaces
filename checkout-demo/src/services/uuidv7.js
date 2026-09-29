/**
 * UUID version 7 (RFC 9562): 48-bit Unix time in milliseconds, then random bits.
 * payment-service only accepts a v7 Idempotency-Key (UuidV7Validator).
 *
 * Layout: tttttttt-tttt-7rrr-Vrrr-rrrrrrrrrrrr
 *   t = timestamp, 7 = version, V = variant (8, 9, a or b), r = random
 */
export function uuidV7() {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);

  // bytes 0-5: milliseconds since 1970, big-endian
  let millis = Date.now();
  for (let i = 5; i >= 0; i--) {
    bytes[i] = millis % 256;
    millis = Math.floor(millis / 256);
  }

  bytes[6] = (bytes[6] & 0x0f) | 0x70; // version 7
  bytes[8] = (bytes[8] & 0x3f) | 0x80; // variant 10xx

  let hex = '';
  for (let i = 0; i < bytes.length; i++) {
    hex += bytes[i].toString(16).padStart(2, '0');
  }
  return hex.substring(0, 8) + '-' + hex.substring(8, 12) + '-' + hex.substring(12, 16) + '-'
    + hex.substring(16, 20) + '-' + hex.substring(20, 32);
}
