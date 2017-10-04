package ch.unil.reactor.compare.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * @author gushakov
 */

@JsonIgnoreProperties(ignoreUnknown = true)
public class PostmanGetResponse {
    private Map<String, Object> args;

    public Map<String, Object> getArgs() {
        return args;
    }

    public void setArgs(Map<String, Object> args) {
        this.args = args;
    }

}
