package se.rocketscien.mcp.springmemorymcpserver.tests.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import se.rocketscien.mcp.springmemorymcpserver.config.MemoryConfig;

import static org.assertj.core.api.Assertions.assertThat;

/** Both salts are required and must differ, otherwise the derived read token would unlock writes. */
class MemoryConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void startsWithTwoDifferentSalts() {
        runner.withPropertyValues("memory.salt-ro=ro", "memory.salt-rw=rw")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesToStartWithoutReadSalt() {
        runner.withPropertyValues("memory.salt-rw=rw")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("memory.salt-ro and memory.salt-rw are required");
                });
    }

    @Test
    void refusesToStartWithoutWriteSalt() {
        runner.withPropertyValues("memory.salt-ro=ro")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("memory.salt-ro and memory.salt-rw are required");
                });
    }

    @Test
    void refusesToStartWithEmptySalt() {
        runner.withPropertyValues("memory.salt-ro=ro", "memory.salt-rw=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesToStartWhenSaltsAreEqual() {
        runner.withPropertyValues("memory.salt-ro=same", "memory.salt-rw=same")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("memory.salt-ro and memory.salt-rw must differ");
                });
    }

    @EnableConfigurationProperties(MemoryConfig.class)
    static class Config {
    }
}
