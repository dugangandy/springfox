/*
 *
 *  Copyright 2016-2017 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */
package springfox.documentation.spring.web.plugins;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import rawhttp.core.RawHttp;
import rawhttp.core.RawHttpResponse;
import springfox.documentation.builders.ExampleBuilder;
import springfox.documentation.builders.ModelSpecificationBuilder;
import springfox.documentation.builders.ResponseBuilder;
import springfox.documentation.schema.Example;
import springfox.documentation.schema.ScalarType;
import springfox.documentation.service.Header;
import springfox.documentation.service.Response;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spi.service.OperationBuilderPlugin;
import springfox.documentation.spi.service.contexts.OperationContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Collections.*;
import static java.util.stream.Collectors.*;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1000)
public class SpringRestDocsOperationBuilderPlugin implements OperationBuilderPlugin {
  private static final Logger LOG = LoggerFactory.getLogger(SpringRestDocsOperationBuilderPlugin.class);

  @Override
  public void apply(OperationContext context) {
    context.operationBuilder()
        .responses(read(context));
  }

  @Override
  public boolean supports(DocumentationType documentationType) {
    return DocumentationType.SWAGGER_12.equals(documentationType)
        || DocumentationType.SWAGGER_2.equals(documentationType);
  }

  /**
   * Provides response messages with examples for given single operation context.
   *
   * @param context representing an operation
   * @return response messages.
   */
  protected Set<Response> read(OperationContext context) {
    Set<Response> ret;
    try {
      PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
      Resource[] resources = resourceResolver.getResources(
          "classpath*:"
              + context.getName()
              + "*/http-response.springfox");
      // TODO: restdocs in safe package name, not directly under restdocs

      ret = Arrays.stream(resources)
          .map(toRawHttpResponse())
          .filter(Objects::nonNull)
          .collect(Collectors.collectingAndThen(
              toMap(
                  RawHttpResponse::getStatusCode,
                  mappingResponseToResponseMessageBuilder(),
                  mergingExamples()
              ),
              responseMessagesMap -> responseMessagesMap.values()
                  .stream()
                  .map(ResponseBuilder::build)
                  .collect(Collectors.toSet())));
    } catch (
        Exception e) {
      LOG.warn("Failed to read restdocs example for {} " + context.getName() + " caused by: " + e.toString());
      ret = Collections.emptySet();
    }
    return ret;

  }

  private Function<Resource, RawHttpResponse<Void>> toRawHttpResponse() {
    return resource -> {
      try (InputStream resourceAsStream = resource.getInputStream()) {
        RawHttp rawHttp = new RawHttp();
        // must extract the body before the stream is closed
        return (RawHttpResponse<Void>) rawHttp.parseResponse(resourceAsStream).eagerly();
      } catch (IOException e) {
        LOG.warn("Failed to read restdocs example for {} "
            + resource.getFilename() + " caused by: " + e.toString());
        return null;
      }
    };
  }

  private BinaryOperator<ResponseBuilder> mergingExamples() {
    return (leftWithSameStatusCode, rightWithSameStatusCode) ->
        leftWithSameStatusCode.examples(rightWithSameStatusCode.build()
            .getExamples());
  }

  private Function<RawHttpResponse<Void>, ResponseBuilder> mappingResponseToResponseMessageBuilder() {
    return parsedResponse -> new ResponseBuilder()
        .code(String.valueOf(parsedResponse.getStatusCode()))
        .examples(toExamples(parsedResponse))
        .headers(toHeaders(parsedResponse));
  }

  @SuppressWarnings("deprecation")
  private Collection<Header> toHeaders(RawHttpResponse<Void> parsedResponse) {
    return parsedResponse.getHeaders()
        .asMap()
        .keySet()
        .stream()
        .map(strings -> new Header(
            strings,
            "",
            new springfox.documentation.schema.ModelRef("string"),
            new ModelSpecificationBuilder()
                .scalarModel(ScalarType.STRING)
                .build()))
        .collect(Collectors.toList());
  }

  private List<Example> toExamples(RawHttpResponse<Void> parsedResponse) {
    return singletonList(new ExampleBuilder()
        .mediaType(getContentType(parsedResponse))
        .value(getBody(parsedResponse))
        .build());
  }

  private String getBody(RawHttpResponse<Void> parsedResponse) {
    return parsedResponse.getBody()
        .map(bodyReader -> {
          String ret = null;
          try {
            ret = bodyReader.asRawString(StandardCharsets.UTF_8);
          } catch (IOException e) {
            LOG.error("failed to read response body", e);
          }
          return ret;
        })
        .orElse(null);
  }

  private String getContentType(RawHttpResponse<Void> parsedResponse) {
    return parsedResponse.getHeaders()
        .get("Content-Type")
        .stream()
        .findFirst()
        .orElse(null);
  }
}
