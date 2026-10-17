import { participantUid } from "./input-validation.js";
import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";

const PLAIN_BYTES = 4096, HEADER_BYTES = 17, NONCE_BYTES = 12, TAG_BYTES = 16;
export const CURSOR_WIRE_LENGTH = Math.ceil((HEADER_BYTES + NONCE_BYTES + PLAIN_BYTES + TAG_BYTES) * 4 / 3);
export const CURSOR_LIFETIME_MS = 300_000;
const ROTATE_MS = 3_600_000, ISSUE_LIMIT = 2 ** 20;
export type CursorScope = { project: string; environment: string; uid: string; trainer: string; workspace: string; pageSize: number };
export type Position = { seconds: number; nanoseconds: number; clientId: string };
export type CursorState = { version: 2; purpose: "clients-list"; filter: "assigned-active"; order: "createdAt-id-v2";
  uid: string; trainer: string; workspace: string; pageSize: number; revision: number; issuedAt: number; expiresAt: number; last: Position };
type Key = { id: Buffer; bytes: Buffer; born: number; issues: number; lastIssued: number };
const invalid = (): never => { throw new Error("invalid-cursor"); };
const aad = (header: Buffer, scope: CursorScope) => Buffer.concat([header, Buffer.from(JSON.stringify([
  "tillfailure-clients", scope.environment, scope.project, scope.uid, scope.trainer, scope.workspace,
  "clients-list", "assigned-active", "createdAt-id-v2", 2, scope.pageSize]))]);

/** Owned by one guarded emulator process; never exported to mobile, disk, logs or fixtures. */
export class ClientCursorKeys {
  private current: Key | undefined;
  private prior: Key[] = [];
  constructor(private readonly clock: () => number = Date.now) {
    const now = clock();
    this.current = { id: randomBytes(16), bytes: randomBytes(32), born: now, issues: 0, lastIssued: now };
  }
  private prune(now: number) { this.prior = this.prior.filter(k => k.lastIssued + CURSOR_LIFETIME_MS > now); }
  retireAll() { this.current = undefined; this.prior = []; }
  seal(scope: CursorScope, state: CursorState): string {
    const now = this.clock(); this.prune(now);
    if (!this.current || now - this.current.born >= ROTATE_MS || this.current.issues >= ISSUE_LIMIT) {
      if (this.current) this.prior.push(this.current);
      this.current = { id: randomBytes(16), bytes: randomBytes(32), born: now, issues: 0, lastIssued: now };
    }
    const key = this.current;
    const body = Buffer.from(JSON.stringify(state));
    if (body.length > PLAIN_BYTES - 4) throw new Error("unavailable");
    const plain = Buffer.alloc(PLAIN_BYTES); plain.writeUInt32BE(body.length); body.copy(plain, 4);
    const header = Buffer.concat([Buffer.from([2]), key.id]), nonce = randomBytes(NONCE_BYTES);
    key.issues++; key.lastIssued = now;
    const cipher = createCipheriv("aes-256-gcm", key.bytes, nonce, { authTagLength: TAG_BYTES });
    cipher.setAAD(aad(header, scope));
    return Buffer.concat([header, nonce, cipher.update(plain), cipher.final(), cipher.getAuthTag()]).toString("base64url");
  }
  open(scope: CursorScope, token: unknown): CursorState {
    try {
      const now = this.clock(); this.prune(now);
      if (typeof token !== "string" || token.length > 6144 || token.length !== CURSOR_WIRE_LENGTH || !/^[A-Za-z0-9_-]+$/.test(token)) invalid();
      const wire = Buffer.from(token as string, "base64url");
      if (wire.toString("base64url") !== token || wire.length !== HEADER_BYTES + NONCE_BYTES + PLAIN_BYTES + TAG_BYTES || wire[0] !== 2) invalid();
      const key = [this.current, ...this.prior].find(k => k?.id.equals(wire.subarray(1, HEADER_BYTES)));
      if (!key) invalid();
      const decipher = createDecipheriv("aes-256-gcm", key!.bytes, wire.subarray(HEADER_BYTES, HEADER_BYTES + NONCE_BYTES), { authTagLength: TAG_BYTES });
      decipher.setAAD(aad(wire.subarray(0, HEADER_BYTES), scope)); decipher.setAuthTag(wire.subarray(-TAG_BYTES));
      // Authentication completes before any plaintext parsing.
      const plain = Buffer.concat([decipher.update(wire.subarray(HEADER_BYTES + NONCE_BYTES, -TAG_BYTES)), decipher.final()]);
      const length = plain.readUInt32BE();
      if (length < 2 || length > PLAIN_BYTES - 4 || plain.subarray(4 + length).some(b => b !== 0)) invalid();
      const s = JSON.parse(plain.subarray(4, 4 + length).toString("utf8"));
      if (!s || Object.keys(s).sort().join() !== "expiresAt,filter,issuedAt,last,order,pageSize,purpose,revision,trainer,uid,version,workspace" ||
          s.version !== 2 || s.purpose !== "clients-list" || s.filter !== "assigned-active" || s.order !== "createdAt-id-v2" ||
          s.uid !== scope.uid || s.trainer !== scope.trainer || s.trainer !== s.uid || s.workspace !== scope.workspace || s.pageSize !== scope.pageSize ||
          !Number.isSafeInteger(s.revision) || s.revision < 1 || s.revision > 2147483646 || !Number.isSafeInteger(s.issuedAt) || !Number.isSafeInteger(s.expiresAt) ||
          s.issuedAt < 0 || s.issuedAt > now || s.expiresAt <= now || s.expiresAt - s.issuedAt !== CURSOR_LIFETIME_MS ||
          !s.last || Object.keys(s.last).sort().join() !== "clientId,nanoseconds,seconds" ||
          !Number.isSafeInteger(s.last.seconds) || s.last.seconds < 0 || s.last.seconds > 253402300799 ||
          !Number.isInteger(s.last.nanoseconds) || s.last.nanoseconds < 0 || s.last.nanoseconds > 999999999) invalid();
      participantUid(s.last.clientId, "clientId");
      return s as CursorState;
    } catch { return invalid(); }
  }
}
