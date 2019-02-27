/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.javascript;

import javax.script.Bindings;
import javax.script.ScriptEngineManager;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.http.stream.TransformableRequestStreamBuilder;
import io.gravitee.gateway.api.http.stream.TransformableResponseStreamBuilder;
import io.gravitee.gateway.api.stream.ReadWriteStream;
import io.gravitee.gateway.api.stream.exception.TransformationException;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyConfiguration;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.api.annotations.OnRequestContent;
import io.gravitee.policy.api.annotations.OnResponse;
import io.gravitee.policy.api.annotations.OnResponseContent;
import io.gravitee.policy.javascript.configuration.JavaScriptPolicyConfiguration;
import io.gravitee.policy.javascript.model.ContentAwareRequest;
import io.gravitee.policy.javascript.model.ContentAwareResponse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 * @author Alexandre Tolstenko (tolstenko at gr1d.io)
 * @author Rafael Lins (rafael at gr1d.io)
 * @author gr1d.io team
 */
public class JavaScriptPolicy {

    private final JavaScriptPolicyConfiguration javaScriptPolicyConfiguration;

    private final static String REQUEST_VARIABLE_NAME = "request";
    private final static String RESPONSE_VARIABLE_NAME = "response";
    private final static String CONTEXT_VARIABLE_NAME = "context";
    private final static String RESULT_VARIABLE_NAME = "result";
    private final static String SCRIPT_ENGINE_NAME = "nashorn";

    private static final ScriptEngineManager SCRIPT_ENGINE_MANAGER = new ScriptEngineManager();
    private static final ScriptEngine SCRIPT_ENGINE = SCRIPT_ENGINE_MANAGER.getEngineByName(SCRIPT_ENGINE_NAME);

    private static final ConcurrentMap<String, Class<?>> sources = new ConcurrentHashMap<>();
    
    public JavaScriptPolicy(PolicyConfiguration javaScriptPolicyConfiguration) {
        this.javaScriptPolicyConfiguration = (JavaScriptPolicyConfiguration)javaScriptPolicyConfiguration;
    }

    @OnRequest
    public void onRequest(Request request, Response response, ExecutionContext executionContext, PolicyChain policyChain) {
        executeScript(request, response, executionContext, policyChain, javaScriptPolicyConfiguration.getOnRequestScript());
    }

    @OnResponse
    public void onResponse(Request request, Response response, ExecutionContext executionContext, PolicyChain policyChain) {
        executeScript(request, response, executionContext, policyChain, javaScriptPolicyConfiguration.getOnResponseScript());
    }

    @OnResponseContent
    public ReadWriteStream onResponseContent(Request request, Response response, ExecutionContext executionContext,
                                             PolicyChain policyChain) {
        String script = javaScriptPolicyConfiguration.getOnResponseContentScript();

        if (script != null && !script.trim().isEmpty()) {
            return TransformableResponseStreamBuilder.on(response).chain(policyChain).transform(
                buffer -> {
                    try {
                        System.out.print(buffer.toString().replace('\n',' '));
                        final String content = executeStreamScript(
                            new ContentAwareRequest(request, null),
                            new ContentAwareResponse(response, buffer.toString()),
                            executionContext,
                            script);
                        return Buffer.buffer(content);
                    } catch (PolicyFailureException ex) {
                        if (ex.getResult().getContentType() != null) {
                            policyChain.streamFailWith(io.gravitee.policy.api.PolicyResult.failure(
                                ex.getResult().getCode(), ex.getResult().getError(),ex.getResult().getContentType()));
                        } else {
                            policyChain.streamFailWith(io.gravitee.policy.api.PolicyResult.failure(
                                ex.getResult().getCode(), ex.getResult().getError()));
                        }
                    } catch (Throwable t) {
                        StringWriter errors = new StringWriter();
                        t.printStackTrace(new PrintWriter(errors));
                        throw new TransformationException("Unable to run javascript: " + t.getMessage() +"\ncaused by:" + t.getCause() + "\nstacktrace:" + errors, t);
                    }
                    return null;
                }
            ).build();
        }
        return null;
    }

    @OnRequestContent
    public ReadWriteStream onRequestContent(Request request, Response response, ExecutionContext executionContext,
                                            PolicyChain policyChain) {
        String script = javaScriptPolicyConfiguration.getOnRequestContentScript();

        if (script != null && !script.trim().isEmpty()) {
            return TransformableRequestStreamBuilder.on(request).chain(policyChain).transform(
                buffer -> {
                    try {
                        System.out.print(buffer.toString().replace('\n',' '));
                        final String content = executeStreamScript(
                            new ContentAwareRequest(request, buffer.toString()),
                            new ContentAwareResponse(response, null),
                                executionContext,
                                script);

                            return Buffer.buffer(content);
                    } catch (PolicyFailureException ex) {
                        if (ex.getResult().getContentType() != null) {
                            policyChain.streamFailWith(io.gravitee.policy.api.PolicyResult.failure(
                                ex.getResult().getCode(), ex.getResult().getError(),ex.getResult().getContentType()));
                        } else {
                            policyChain.streamFailWith(io.gravitee.policy.api.PolicyResult.failure(
                                ex.getResult().getCode(), ex.getResult().getError()));
                        }
                    } catch (Throwable t) {
                        throw new TransformationException("Unable to run Groovy script: " + t.getMessage(), t);
                    }
                    return null;
                }
            ).build();
        }

        return null;
    }

    private String executeScript(Request request, Response response, ExecutionContext executionContext,
                                       String script, PolicyResult policyResult) throws ScriptException {

        Bindings bindings = SCRIPT_ENGINE.createBindings();
        bindings.put(REQUEST_VARIABLE_NAME, new ContentAwareRequest(request, null));
        bindings.put(RESPONSE_VARIABLE_NAME, new ContentAwareResponse(response, null));
        bindings.put(CONTEXT_VARIABLE_NAME, executionContext);
        bindings.put(RESULT_VARIABLE_NAME, policyResult);

        // And run script
        return Optional.ofNullable(SCRIPT_ENGINE.eval(script, bindings))
                .map(Object::toString)
                .orElse(null);
    }

    private String executeScript(Request request, Response response, ExecutionContext executionContext,
                                 PolicyChain policyChain, String script) {
        if (script == null || script.trim().isEmpty()) {
            policyChain.doNext(request, response);
        } else {
            try {
                // Prepare binding
                PolicyResult policyResult = new PolicyResult();
                executeScript(request, response, executionContext, script, policyResult);

                if (policyResult.getState() == PolicyResult.State.SUCCESS) {
                    policyChain.doNext(request, response);
                } else {
                    if (policyResult.getContentType() != null) {
                        policyChain.failWith(io.gravitee.policy.api.PolicyResult.failure(
                                policyResult.getCode(),
                                policyResult.getError(),
                                policyResult.getContentType()));
                    } else {
                        policyChain.failWith(io.gravitee.policy.api.PolicyResult.failure(
                                policyResult.getCode(),
                                policyResult.getError()));
                    }
                }
            } catch (Throwable t) {
                policyChain.failWith(io.gravitee.policy.api.PolicyResult.failure(t.getMessage()));
            }
        }

        return null;
    }

    private String executeStreamScript(Request request, Response response, ExecutionContext executionContext,
                                       String script) throws PolicyFailureException, ScriptException {
        // Prepare binding
        PolicyResult policyResult = new PolicyResult();
        String content = executeScript(request, response, executionContext, script, policyResult);

        if (policyResult.getState() == PolicyResult.State.FAILURE) {
            throw new PolicyFailureException(policyResult);
        }

        return content;
    }

    private static class PolicyFailureException extends Exception {
        private final PolicyResult result;
        PolicyFailureException(PolicyResult result) {
            this.result = result;
        }
        public PolicyResult getResult() {
            return result;
        }
    }
}
