# n8n

Three n8n workflows used as profiles. To the application they are clients
like any model; what happens behind the webhook is the workflow's business.

- `triage` sends a support ticket and gets back JSON: category, priority and a
  first reply. The workflow holds the prompt and asks the model for JSON.
- `summarize` sends a URL. The workflow fetches the page, strips it to text
  and has the model summarise it: something a model alone cannot do.
- `ask-data` sends a question. An n8n AI Agent answers it from a small sales
  table through a tool, the shape of a real data agent behind a webhook.

All three run on Ollama on the host. The first two call it directly and pass
its token counts back, so they are accounted for like a call to a model. The
agent's nodes do not hand out token counts, so `ask-data` has no token paths
and its usage is unknown: logged as `?`, never as zero. The profile's `model`,
`temperature` and `max-tokens` reach the workflow through the request
template.

Start n8n, build the library, then run the sample from this directory:

```sh
docker compose up -d                 # n8n on localhost:5678, workflows loaded and published
mvn -f ../.. install -DskipTests
mvn spring-boot:run                  # or with a page of your own: -Dspring-boot.run.arguments=https://...
```

Ollama has to be running on the host with the model pulled
(`ollama pull llama3.1`; the agent needs a model that can call tools). The
workflows can be opened in the editor at http://localhost:5678. They are
imported afresh on every start; the n8n account and anything else made in the
editor are kept in the `n8n-data` volume.

| variable       | default                             | used by                                   |
|----------------|-------------------------------------|-------------------------------------------|
| `N8N_KEY`      | `demo-key`                          | all: the `X-Api-Key` the webhooks check   |
| `OLLAMA_MODEL` | `llama3.1`                          | all: the profiles' model                  |
| `OLLAMA_URL`   | `http://host.docker.internal:11434` | n8n                                       |
| `N8N_URL`      | `http://localhost:5678`             | the application                           |

The workflows are in [`n8n/workflows`](n8n/workflows). Each starts with a
webhook with header auth and ends with a response the profile reads: `$.output`
for the text, and for the first two `$.usage.*` for the tokens.
