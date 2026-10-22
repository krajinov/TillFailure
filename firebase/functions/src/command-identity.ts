import { Firestore, Transaction, DocumentSnapshot } from "firebase-admin/firestore";

// Demo-only namespace; coverage is never inferred from the marker or an absent receipt.
export const RESOLUTION_GENERATION = "b91f0176ec74475e8cf7406e25b7e9d3";
export const RESOLUTION_CONFIG_PATH = "commandResolutionConfig/current";
export async function requireCommandGeneration(tx: Transaction, db: Firestore, key: string): Promise<DocumentSnapshot | undefined> {
  if (!key.startsWith("or1-")) return;
  if (!new RegExp(`^or1-${RESOLUTION_GENERATION}-[A-Za-z0-9_-]{1,80}$`).test(key) || key.length > 128) throw new Error("outcome-unavailable");
  const c = await tx.get(db.doc(RESOLUTION_CONFIG_PATH));
  const provenance = process.env.TF_RESOLUTION_PROVENANCE_ID;
  if (!provenance || !/^[A-Za-z0-9_-]{1,80}$/.test(provenance) || c.get("schemaVersion") !== 1 ||
      c.get("generation") !== RESOLUTION_GENERATION || c.get("provenanceId") !== provenance ||
      c.get("historyVerified") !== true || c.get("executionEnabled") !== true) throw new Error("outcome-unavailable");
  return c;
}
