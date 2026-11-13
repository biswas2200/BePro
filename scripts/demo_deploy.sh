#!/usr/bin/env bash
# Deploys the Be PRO backend to Cloud Run for a demo (Cloud SQL, secrets, image already created).
# Public URL, but every data route still requires the app's JWT; tokens require the pairing secret.
# Undo everything with scripts/demo_teardown.sh.
set -euo pipefail
P=smart-resume-analyzer-a7x2
R=asia-south1
I=bepro-db
SA=bepro-run@$P.iam.gserviceaccount.com
IMG=$R-docker.pkg.dev/$P/bepro/backend:demo

gcloud run deploy bepro --project=$P --image=$IMG --region=$R --service-account=$SA \
  --allow-unauthenticated --memory=1Gi --cpu=1 --cpu-boost --min-instances=1 --max-instances=2 \
  --add-cloudsql-instances=$P:$R:$I --labels=app=bepro,purpose=demo \
  --set-env-vars="^@^DB_URL=jdbc:postgresql:///bepro?cloudSqlInstance=$P:$R:$I&socketFactory=com.google.cloud.sql.postgres.SocketFactory@DB_USER=bepro@DB_POOL_SIZE=3@BEPRO_ZONE=Asia/Kolkata@BEPRO_USER_ID=me@BEPRO_AI_PROVIDER=vertex@GOOGLE_CLOUD_PROJECT=$P@BEPRO_VERTEX_LOCATION=global@BEPRO_VERTEX_MODEL=gemini-3.5-flash@BEPRO_DIGEST_SCHEDULER=false" \
  --set-secrets="DB_PASSWORD=bepro-db-password:latest,BEPRO_DEVICE_SECRET=bepro-device-secret:latest,BEPRO_JWT_SECRET=bepro-jwt-secret:latest,BEPRO_INTERNAL_TOKEN=bepro-internal-token:latest" \
  -q

URL=$(gcloud run services describe bepro --project=$P --region=$R --format='value(status.url)')
echo "URL=$URL"
curl -s "$URL/v1/health"; echo
