package it.pratico.garminmcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootApplication
@EnableConfigurationProperties(GarminProperties.class)
public class GarminMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(GarminMcpApplication.class, args);
    }

    /** Exposes every @Tool method of GarminTools as an MCP tool. */
    @Bean
    ToolCallbackProvider garminToolCallbacks(GarminTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    /** Exposes the prompts of GarminPrompts as MCP prompts. */
    @Bean
    List<SyncPromptSpecification> garminPromptSpecifications(GarminPrompts prompts) {
        return prompts.specifications();
    }
}
