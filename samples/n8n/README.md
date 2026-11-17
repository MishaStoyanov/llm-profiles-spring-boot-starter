# n8n

Two n8n workflows used as profiles. To the application they are clients like
any model; what happens behind the webhook is the workflow's business.

- `triage` sends a support ticket and gets back JSON: category, priority and a
  first reply. The workflow holds the prompt and asks the model for JSON.
- `summarize` sends a URL. The workflow fetches the page, strips it to text
  and has the model summarise it: something a model alone cannot do.

Both run on Ollama on the host, and pass its token counts back, so every call
is accounted for like a call to a model. The profile's `model`, `temperature`
and `max-tokens` reach the workflow through the request template.

Start n8n, build the library, then run the sample from this directory:

```sh
docker compose up -d                 # n8n on localhost:5678, workflows loaded and published
mvn -f ../.. install -DskipTests
mvn spring-boot:run                  # or with a page of your own: -Dspring-boot.run.arguments=https://...
```

Ollama has to be running on the host with the model pulled
(`ollama pull llama3.1`). The workflows can be opened in the editor at
http://localhost:5678; nothing is kept when the container goes.

| variable       | default                             | used by                                   |
|----------------|-------------------------------------|-------------------------------------------|
| `N8N_KEY`      | `demo-key`                          | both: the `X-Api-Key` the webhooks check  |
| `OLLAMA_MODEL` | `llama3.1`                          | both: the profiles' model                 |
| `OLLAMA_URL`   | `http://host.docker.internal:11434` | n8n                                       |
| `N8N_URL`      | `http://localhost:5678`             | the application                           |

The workflows are in [`n8n/workflows`](n8n/workflows): a webhook with header
auth, a step that builds the request for Ollama, the call, and a response of
`{output, model, usage: {input, output}}` that the profiles read with
`$.output` and `$.usage.*`.
