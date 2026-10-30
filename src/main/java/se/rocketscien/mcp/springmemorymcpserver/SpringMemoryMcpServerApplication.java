package se.rocketscien.mcp.springmemorymcpserver;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import se.rocketscien.mcp.springmemorymcpserver.tools.MemoryMcpServerTools;

@ConfigurationPropertiesScan
@SpringBootApplication
class SpringMemoryMcpServerApplication {

    @Bean
    ToolCallbackProvider tools(MemoryMcpServerTools memoryMcpServerTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(memoryMcpServerTools)
                .build();
    }

    static void main(String[] args) {
        SpringApplication.run(SpringMemoryMcpServerApplication.class, args);
    }
}
