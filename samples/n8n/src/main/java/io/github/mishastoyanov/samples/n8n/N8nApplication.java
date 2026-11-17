package io.github.mishastoyanov.samples.n8n;

import io.github.mishastoyanov.llmprofiles.LlmClient;
import io.github.mishastoyanov.llmprofiles.LlmResponse;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;

/**
 * Two n8n workflows used as profiles: one triages a support ticket, the other summarises a web
 * page it fetches itself. To the code they are clients like any model.
 */
@SpringBootApplication
public class N8nApplication {

    public static void main(String[] args) {
        SpringApplication.run(N8nApplication.class, args);
    }

    @Component
    static class Demo implements ApplicationRunner {

        private static final String TICKET = """
                Hi, I was charged twice for my subscription this month (order 5521).
                Please refund one of the payments, I need the money back before Friday.""";

        private static final String PAGE = "https://en.wikipedia.org/wiki/Spring_Framework";

        private final LlmClient triage;
        private final LlmClient summarize;

        Demo(LlmClient triage, LlmClient summarize) {
            this.triage = triage;
            this.summarize = summarize;
        }

        @Override
        public void run(ApplicationArguments args) {
            show("triage", triage.withTag("ticket", "5521").call(TICKET));
            String page = args.getNonOptionArgs().isEmpty() ? PAGE : args.getNonOptionArgs().getFirst();
            show("summarize " + page, summarize.call(page));
        }

        private static void show(String what, LlmResponse response) {
            System.out.println("== " + what);
            System.out.println(response.text());
            System.out.println("-- " + response.latency().toMillis() + " ms, " + response.usage());
            System.out.println();
        }
    }
}
