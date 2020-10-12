/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 ~ Licensed to the Apache Software Foundation (ASF) under one
 ~ or more contributor license agreements.  See the NOTICE file
 ~ distributed with this work for additional information
 ~ regarding copyright ownership.  The ASF licenses this file
 ~ to you under the Apache License, Version 2.0 (the
 ~ "License"); you may not use this file except in compliance
 ~ with the License.  You may obtain a copy of the License at
 ~
 ~   http://www.apache.org/licenses/LICENSE-2.0
 ~
 ~ Unless required by applicable law or agreed to in writing,
 ~ software distributed under the License is distributed on an
 ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 ~ KIND, either express or implied.  See the License for the
 ~ specific language governing permissions and limitations
 ~ under the License.
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/
package org.apache.sling.graphql.core.engine;

import java.util.List;
import java.util.Map;

import javax.script.ScriptException;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.graphql.api.SchemaProvider;
import org.apache.sling.graphql.api.SlingDataFetcher;
import org.apache.sling.graphql.api.SlingGraphQLException;
import org.apache.sling.graphql.api.engine.QueryExecutor;
import org.apache.sling.graphql.api.engine.ValidationResult;
import org.apache.sling.graphql.core.scalars.SlingScalarsProvider;
import org.apache.sling.graphql.core.schema.RankedSchemaProviders;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.ParseAndValidate;
import graphql.ParseAndValidateResult;
import graphql.language.Argument;
import graphql.language.Directive;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

@Component(
        service = QueryExecutor.class
)
public class DefaultQueryExecutor implements QueryExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultQueryExecutor.class);

    public static final String FETCHER_DIRECTIVE = "fetcher";
    public static final String FETCHER_NAME = "name";
    public static final String FETCHER_OPTIONS = "options";
    public static final String FETCHER_SOURCE = "source";

    @Reference
    private RankedSchemaProviders schemaProvider;

    @Reference
    private SlingDataFetcherSelector dataFetcherSelector;

    @Reference
    private SlingScalarsProvider scalarsProvider;

    @Override
    public ValidationResult validate(@NotNull String query, @NotNull Map<String, Object> variables, @NotNull Resource queryResource,
                                     @NotNull String[] selectors) {
        try {
            String schemaDef = prepareSchemaDefinition(schemaProvider, queryResource, selectors);
            LOGGER.debug("Resource {} maps to GQL schema {}", queryResource.getPath(), schemaDef);
            final GraphQLSchema schema =
                    buildSchema(schemaDef, dataFetcherSelector, scalarsProvider, queryResource);
            ExecutionInput executionInput = ExecutionInput.newExecutionInput()
                    .query(query)
                    .variables(variables)
                    .build();
            ParseAndValidateResult parseAndValidateResult = ParseAndValidate.parseAndValidate(schema, executionInput);
            if (!parseAndValidateResult.isFailure()) {
                return DefaultValidationResult.Builder.newBuilder().withValidFlag(true).build();
            }
            DefaultValidationResult.Builder validationResultBuilder = DefaultValidationResult.Builder.newBuilder().withValidFlag(false);
            for (GraphQLError error : parseAndValidateResult.getErrors()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Error: type=").append(error.getErrorType().toString()).append("; ");
                sb.append("message=").append(error.getMessage()).append("; ");
                for (SourceLocation location : error.getLocations()) {
                    sb.append("location=").append(location.getLine()).append(",").append(location.getColumn()).append(";");
                }
                validationResultBuilder.withErrorMessage(sb.toString());
            }
            return validationResultBuilder.build();
        } catch (Exception e) {
            return DefaultValidationResult.Builder.newBuilder().withValidFlag(false).withErrorMessage(e.getMessage()).build();
        }
    }

    @Override
    public @NotNull Map<String, Object> execute(@NotNull String query, @NotNull Map<String, Object> variables,
                                                @NotNull Resource queryResource, @NotNull String[] selectors) {
        String schemaDef = null;
        try {
            schemaDef = prepareSchemaDefinition(schemaProvider, queryResource, selectors);
            LOGGER.debug("Resource {} maps to GQL schema {}", queryResource.getPath(), schemaDef);
            final GraphQLSchema schema = buildSchema(schemaDef, dataFetcherSelector, scalarsProvider, queryResource);
            final GraphQL graphQL = GraphQL.newGraphQL(schema).build();
            LOGGER.debug("Executing query\n[{}]\nat [{}] with variables [{}]", query, queryResource.getPath(), variables);
            ExecutionInput ei = ExecutionInput.newExecutionInput()
                    .query(query)
                    .variables(variables)
                    .build();
            final ExecutionResult result = graphQL.execute(ei);
            if (!result.getErrors().isEmpty()) {
                StringBuilder errors = new StringBuilder();
                for (GraphQLError error : result.getErrors()) {
                    errors.append("Error: type=").append(error.getErrorType().toString()).append("; message=").append(error.getMessage()).append(System.lineSeparator());
                    for (SourceLocation location : error.getLocations()) {
                        errors.append("location=").append(location.getLine()).append(",").append(location.getColumn()).append(";");
                    }
                }
                throw new SlingGraphQLException(String.format("Query failed for Resource %s: schema=%s, query=%s%nErrors:%n%s",
                        queryResource.getPath(), schemaDef, query, errors.toString()));
            }
            LOGGER.debug("ExecutionResult.isDataPresent={}", result.isDataPresent());
            return result.toSpecification();
        } catch (SlingGraphQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SlingGraphQLException(
                    String.format("Query failed for Resource %s: schema=%s, query=%s", queryResource.getPath(), schemaDef, query), e);
        }
    }

    private GraphQLSchema buildSchema(String sdl, SlingDataFetcherSelector fetchers, SlingScalarsProvider scalarsProvider,
                                             Resource currentResource) {
        TypeDefinitionRegistry typeRegistry = new SchemaParser().parse(sdl);
        Iterable<GraphQLScalarType> scalars = scalarsProvider.getCustomScalars(typeRegistry.scalars());
        RuntimeWiring runtimeWiring = buildWiring(typeRegistry, fetchers, scalars, currentResource);
        SchemaGenerator schemaGenerator = new SchemaGenerator();
        return schemaGenerator.makeExecutableSchema(typeRegistry, runtimeWiring);
    }

    private RuntimeWiring buildWiring(TypeDefinitionRegistry typeRegistry, SlingDataFetcherSelector fetchers,
                                             Iterable<GraphQLScalarType> scalars, Resource r) {
        List<ObjectTypeDefinition> types = typeRegistry.getTypes(ObjectTypeDefinition.class);
        RuntimeWiring.Builder builder = RuntimeWiring.newRuntimeWiring();
        for (ObjectTypeDefinition type : types) {
            builder.type(type.getName(), typeWiring -> {
                for (FieldDefinition field : type.getFieldDefinitions()) {
                    try {
                        DataFetcher<Object> fetcher = getDataFetcher(field, fetchers, r);
                        if (fetcher != null) {
                            typeWiring.dataFetcher(field.getName(), fetcher);
                        }
                    } catch (SlingGraphQLException e) {
                        throw  e;
                    } catch (Exception e) {
                        throw new SlingGraphQLException("Exception while building wiring.", e);
                    }
                }
                return typeWiring;
            });
        }
        scalars.forEach(builder::scalar);
        return builder.build();
    }

    private String getDirectiveArgumentValue(Directive d, String name) {
        final Argument a = d.getArgument(name);
        if(a != null && a.getValue() instanceof StringValue) {
            return ((StringValue)a.getValue()).getValue();
        }
        return null;
    }

    private @NotNull String validateFetcherName(String name) {
        if (SlingDataFetcherSelector.nameMatchesPattern(name)) {
            return name;
        }
        throw new SlingGraphQLException(String.format("Invalid fetcher name %s, does not match %s",
                name, SlingDataFetcherSelector.FETCHER_NAME_PATTERN));
    }

    private DataFetcher<Object> getDataFetcher(FieldDefinition field, SlingDataFetcherSelector fetchers, Resource currentResource)
            {
        DataFetcher<Object> result = null;
        final Directive d =field.getDirective(FETCHER_DIRECTIVE);
        if(d != null) {
            final String name = validateFetcherName(getDirectiveArgumentValue(d, FETCHER_NAME));
            final String options = getDirectiveArgumentValue(d, FETCHER_OPTIONS);
            final String source = getDirectiveArgumentValue(d, FETCHER_SOURCE);
            SlingDataFetcher<Object> f = fetchers.getSlingFetcher(name);
            if(f != null) {
                result = new SlingDataFetcherWrapper<>(f, currentResource, options, source);
            }
        }
        return result;
    }

    private @Nullable String prepareSchemaDefinition(@NotNull SchemaProvider schemaProvider,
                                                            @NotNull org.apache.sling.api.resource.Resource resource,
                                                            @NotNull String[] selectors) throws ScriptException {
        try {
            return schemaProvider.getSchema(resource, selectors);
        } catch (Exception e) {
            final ScriptException up = new ScriptException("Schema provider failed");
            up.initCause(e);
            LOGGER.info("Schema provider Exception", up);
            throw up;
        }
    }
}