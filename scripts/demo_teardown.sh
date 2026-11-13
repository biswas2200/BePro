#!/usr/bin/env bash
# Removes everything the Be PRO demo created in GCP and points the emulator app back at the local
# backend. Keeps the Vertex AI API enabled (used by the local backend) and your other projects' data.
#
#   bash scripts/demo_teardown.sh
set -uo pipefail
P=smart-resume-analyzer-a7x2
R=asia-south1
SA=bepro-run@$P.iam.gserviceaccount.com
ADB=~/Android/Sdk/platform-tools/adb
cd "$(dirname "$0")/.."

echo "This deletes from project $P: Cloud Run service 'bepro', Cloud SQL instance 'bepro-db' (all demo data),"
echo "secrets bepro-*, Artifact Registry repo 'bepro', service account bepro-run and its role bindings."
read -r -p "Type 'delete' to continue: " ok
[ "$ok" = "delete" ] || { echo "Cancelled."; exit 1; }

step() { echo; echo "== $*"; }

step "Cloud Scheduler job (if any)"
gcloud scheduler jobs delete bepro-weekly-digest --project=$P --location=$R -q 2>/dev/null || echo "none"

step "Cloud Run service"
gcloud run services delete bepro --project=$P --region=$R -q || true

step "Cloud SQL instance (stops the main cost)"
gcloud sql instances delete bepro-db --project=$P -q || true

step "Secrets"
for s in bepro-db-password bepro-device-secret bepro-jwt-secret bepro-internal-token; do
  gcloud secrets delete $s --project=$P -q || true
done

step "Artifact Registry repo"
gcloud artifacts repositories delete bepro --project=$P --location=$R -q || true

step "Service account roles and account"
for role in roles/cloudsql.client roles/aiplatform.user roles/secretmanager.secretAccessor; do
  gcloud projects remove-iam-policy-binding $P --member=serviceAccount:$SA --role=$role --condition=None -q >/dev/null \
    && echo "removed $role" || true
done
gcloud iam service-accounts delete $SA --project=$P -q || true

step "Local backend"
docker compose up -d
for i in $(seq 1 30); do curl -sf localhost:8080/v1/health >/dev/null && break; sleep 2; done
curl -s localhost:8080/v1/health; echo

step "Emulator app → local backend"
if [ -x "$ADB" ] && "$ADB" get-state >/dev/null 2>&1; then
  "$ADB" reverse tcp:8080 tcp:8080
  echo "In the app: Settings → Unpair, then pair with server http://localhost:8080 and secret:"
  grep '^BEPRO_DEVICE_SECRET' .env | cut -d= -f2
else
  echo "No emulator running; nothing to do."
fi

step "Left in place"
echo "- Vertex AI API (local backend uses it for Gemini; costs nothing when idle)"
echo "- Other APIs enabled for the demo (run, sqladmin, artifactregistry, secretmanager, cloudscheduler, cloudbuild): free when unused"
echo "Done."
