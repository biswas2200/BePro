# Deploying the backend to GCP (Cloud Run + Cloud SQL + Vertex AI)

Run these from the repo root, with the `gcloud` CLI logged in. Replace the values in the first block.
Expected cost for one user: Cloud Run ≈ free tier, Cloud SQL `db-f1-micro` ≈ $9–10/month, Gemini a few cents/month.

```bash
PROJECT=your-gcp-project-id
REGION=asia-south1            # Mumbai, closest to India
INSTANCE=bepro-db
SA=bepro-run@$PROJECT.iam.gserviceaccount.com
IMAGE=$REGION-docker.pkg.dev/$PROJECT/bepro/backend
```

## 1. Enable services

```bash
gcloud config set project $PROJECT
gcloud services enable run.googleapis.com sqladmin.googleapis.com artifactregistry.googleapis.com \
  secretmanager.googleapis.com aiplatform.googleapis.com cloudscheduler.googleapis.com
```

## 2. Database

```bash
gcloud sql instances create $INSTANCE --database-version=POSTGRES_17 --tier=db-f1-micro \
  --region=$REGION --storage-size=10 --edition=enterprise
gcloud sql databases create bepro --instance=$INSTANCE
DB_PASSWORD=$(openssl rand -base64 24)
gcloud sql users create bepro --instance=$INSTANCE --password="$DB_PASSWORD"
```

## 3. Secrets

```bash
printf '%s' "$DB_PASSWORD"            | gcloud secrets create bepro-db-password --data-file=-
openssl rand -base64 24 | tr -d '\n'  | gcloud secrets create bepro-device-secret --data-file=-
openssl rand -base64 48 | tr -d '\n'  | gcloud secrets create bepro-jwt-secret --data-file=-
openssl rand -hex 24    | tr -d '\n'  | gcloud secrets create bepro-internal-token --data-file=-

# You'll type this into the phone's setup screen:
gcloud secrets versions access latest --secret=bepro-device-secret; echo
```

## 4. Service account

```bash
gcloud iam service-accounts create bepro-run
for role in roles/cloudsql.client roles/aiplatform.user roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding $PROJECT --member=serviceAccount:$SA --role=$role
done
```

## 5. Build and push the image (Jib, no Docker daemon needed)

```bash
gcloud artifacts repositories create bepro --repository-format=docker --location=$REGION
gcloud auth configure-docker $REGION-docker.pkg.dev
(cd backend && ./mvnw -q compile jib:build -Dimage=$IMAGE)
```

## 6. Deploy

```bash
gcloud run deploy bepro --image=$IMAGE --region=$REGION --service-account=$SA \
  --allow-unauthenticated --memory=512Mi --cpu=1 --cpu-boost --min-instances=0 --max-instances=2 \
  --add-cloudsql-instances=$PROJECT:$REGION:$INSTANCE \
  --set-env-vars="DB_URL=jdbc:postgresql:///bepro?cloudSqlInstance=$PROJECT:$REGION:$INSTANCE&socketFactory=com.google.cloud.sql.postgres.SocketFactory,DB_USER=bepro,DB_POOL_SIZE=3,BEPRO_ZONE=Asia/Kolkata,BEPRO_AI_PROVIDER=vertex,GOOGLE_CLOUD_PROJECT=$PROJECT,BEPRO_VERTEX_LOCATION=global" \
  --set-secrets="DB_PASSWORD=bepro-db-password:latest,BEPRO_DEVICE_SECRET=bepro-device-secret:latest,BEPRO_JWT_SECRET=bepro-jwt-secret:latest,BEPRO_INTERNAL_TOKEN=bepro-internal-token:latest"

URL=$(gcloud run services describe bepro --region=$REGION --format='value(status.url)')
curl $URL/v1/health
```

`--allow-unauthenticated` only means Cloud Run doesn't add Google sign-in in front. Every API route
except `/v1/health` and `/v1/auth/token` still needs the app's JWT, and the token endpoint needs the
pairing secret.

## 7. Weekly digest (Sunday 08:00 IST)

```bash
gcloud scheduler jobs create http bepro-weekly-digest --location=$REGION \
  --schedule="0 8 * * 0" --time-zone="Asia/Kolkata" \
  --uri="$URL/v1/internal/digest/run" --http-method=POST \
  --headers="X-Bepro-Internal-Token=$(gcloud secrets versions access latest --secret=bepro-internal-token)" \
  --attempt-deadline=120s
```

Trigger it once by hand to test: `gcloud scheduler jobs run bepro-weekly-digest --location=$REGION`.

## 8. Budget alert

Billing → Budgets & alerts → create a $15/month budget on this project with email alerts at 50/90/100%.

## Model choice

`BEPRO_VERTEX_MODEL` defaults to `gemini-3.5-flash`. If Vertex AI reports the model isn't available in
`global`, list what your project can use in the Vertex AI Model Garden and set the variable to that ID
(`gcloud run services update bepro --region=$REGION --update-env-vars=BEPRO_VERTEX_MODEL=...`).

## Cheaper database option

Neon's or Supabase's free Postgres tier works too: set `DB_URL=jdbc:postgresql://HOST/DB?sslmode=require`,
`DB_USER`, `DB_PASSWORD` and drop `--add-cloudsql-instances`. Data then lives outside your GCP project.
