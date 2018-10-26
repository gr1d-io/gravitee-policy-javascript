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
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.api.annotations.OnRequestContent;
import io.gravitee.policy.api.annotations.OnResponse;
import io.gravitee.policy.api.annotations.OnResponseContent;
import io.gravitee.policy.javascript.model.ContentAwareRequest;
import io.gravitee.policy.javascript.model.ContentAwareResponse;

import java.util.Optional;
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

    private final io.gravitee.policy.javascript.configuration.JavaScriptPolicyConfiguration javaScriptPolicyConfiguration;

    private final static String REQUEST_VARIABLE_NAME = "request";
    private final static String RESPONSE_VARIABLE_NAME = "response";
    private final static String CONTEXT_VARIABLE_NAME = "context";
    private final static String RESULT_VARIABLE_NAME = "result";

    private static final ScriptEngineManager scriptEngineManager = new ScriptEngineManager();
    private static final ScriptEngine NASHORN = scriptEngineManager.getEngineByName("nashorn");

    private static final ConcurrentMap<String, Class<?>> sources = new ConcurrentHashMap<>();

    public JavaScriptPolicy(io.gravitee.policy.javascript.configuration.JavaScriptPolicyConfiguration javaScriptPolicyConfiguration) {
        this.javaScriptPolicyConfiguration = javaScriptPolicyConfiguration;
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
            return TransformableResponseStreamBuilder
                    .on(response)
                    .chain(policyChain)
                    .transform(
                            buffer -> {
                                try {
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
                                    throw new TransformationException("Unable to run Groovy script: " + t.getMessage(), t);
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
            return TransformableRequestStreamBuilder
                    .on(request)
                    .chain(policyChain)
                    .transform(
                            buffer -> {
                                try {
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

        Bindings bindings = NASHORN.createBindings();
        bindings.put(REQUEST_VARIABLE_NAME, new ContentAwareRequest(request, null));
        bindings.put(RESPONSE_VARIABLE_NAME, new ContentAwareResponse(response, null));
        bindings.put(CONTEXT_VARIABLE_NAME, executionContext);
        bindings.put(RESULT_VARIABLE_NAME, policyResult);

        // And run script
        return Optional.ofNullable(NASHORN.eval(script, bindings))
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
