#!/bin/sh
# Loads the demo into a fresh n8n and starts it: the credential the webhooks check, both
# workflows, published. Nothing is kept between runs.
set -e

mkdir -p /tmp/demo
cat > /tmp/demo/credentials.json <<EOF
[{"id": "llmprofDemoKey01", "name": "Demo webhook key", "type": "httpHeaderAuth",
  "data": {"name": "X-Api-Key", "value": "${N8N_KEY}"}}]
EOF

n8n import:credentials --input=/tmp/demo/credentials.json
n8n import:workflow --separate --input=/demo/workflows
for id in llmprofTriage001 llmprofSummary01; do
    n8n publish:workflow --id="$id"
done

exec n8n start
