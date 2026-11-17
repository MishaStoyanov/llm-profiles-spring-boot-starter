#!/bin/sh
# Loads the demo into n8n and starts it: the credentials the workflows use and the workflows
# themselves, published. Runs on every start, so the demo is always as in this directory;
# anything else made in the editor is kept.
set -e

mkdir -p /tmp/demo
cat > /tmp/demo/credentials.json <<EOF
[{"id": "llmprofDemoKey01", "name": "Demo webhook key", "type": "httpHeaderAuth",
  "data": {"name": "X-Api-Key", "value": "${N8N_KEY}"}},
 {"id": "llmprofOllama001", "name": "Local Ollama", "type": "ollamaApi",
  "data": {"baseUrl": "${OLLAMA_URL}"}}]
EOF

n8n import:credentials --input=/tmp/demo/credentials.json
n8n import:workflow --separate --input=/demo/workflows
for id in llmprofTriage001 llmprofSummary01 llmprofAskData01; do
    n8n publish:workflow --id="$id"
done

exec n8n start
