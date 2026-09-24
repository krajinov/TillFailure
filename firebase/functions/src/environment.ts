import { getApps, initializeApp } from "firebase-admin/app";
import { Firestore, getFirestore } from "firebase-admin/firestore";

export const SPIKE_PROJECT_ID = "demo-tillfailure-m3";

export function requireEmulatorEnvironment(): void {
  const projectId = process.env.GCLOUD_PROJECT ?? process.env.GOOGLE_CLOUD_PROJECT;
  if (projectId !== SPIKE_PROJECT_ID) {
    throw new Error(`Emulator spike requires project ${SPIKE_PROJECT_ID}`);
  }
  if (!process.env.FIRESTORE_EMULATOR_HOST) {
    throw new Error("FIRESTORE_EMULATOR_HOST is required; refusing any fallback");
  }
}

export function emulatorFirestore(): Firestore {
  requireEmulatorEnvironment();
  const app = getApps()[0] ?? initializeApp({ projectId: SPIKE_PROJECT_ID });
  return getFirestore(app);
}
