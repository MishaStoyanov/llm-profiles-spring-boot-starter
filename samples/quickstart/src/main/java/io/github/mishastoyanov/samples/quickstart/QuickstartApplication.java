package io.github.mishastoyanov.samples.quickstart;

import io.github.mishastoyanov.llmprofiles.LlmClient;
import io.github.mishastoyanov.llmprofiles.LlmResponse;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;

/**
 * Asks one question twice, once as a call and once as a stream, through the profile
 * {@code assistant}. Which vendor answers is up to the Spring profile it runs with; see the README.
 */
@SpringBootApplication
public class QuickstartApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuickstartApplication.class, args);
    }

    @Component
    static class Ask implements ApplicationRunner {

        private final LlmClient assistant;

        Ask(LlmClient assistant) {
            this.assistant = assistant;
        }

        @Override
        public void run(ApplicationArguments args) {
            String question = args.getNonOptionArgs().isEmpty()
                    ? "In one sentence: what is a Spring Boot starter?"
                    : String.join(" ", args.getNonOptionArgs());

            LlmResponse response = assistant.withTag("run", "quickstart").call(question);
            System.out.println(response.text());
            System.out.println("-- " + response.model() + ", " + response.latency().toMillis() + " ms, "
                    + response.usage());

            assistant.withTag("run", "quickstart").stream(question)
                    .doOnNext(System.out::print)
                    .blockLast();
            System.out.println();
        }
    }
}
