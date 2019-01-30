package flashbot.strategies;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kjetland.jackson.jsonSchema.JsonSchemaConfig;
import com.kjetland.jackson.jsonSchema.JsonSchemaGenerator;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaInject;
import flashbot.core.*;
import flashbot.models.core.*;
import flashbot.server.StrategyInfo;
import flashbot.util.JavaUtils;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class MarketMakerJava extends JavaStrategy<MarketMakerJava.MarketMakerJavaParams> {

    class MarketMakerJavaParams {
        @JsonSchemaInject(jsonSupplierViaLookup = "market")
        public String market;

        MarketMakerJavaParams(String market) {
            this.market = market;
        }
    }

    private SessionLoader infoLoader;

    private Supplier<JsonNode> marketSupplier = () -> {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode schema = mapper.createObjectNode();
        ArrayNode values = schema.putObject("items").putArray("enum");
        JavaUtils.toJava(infoLoader.exchanges()).forEach(values::add);
        return schema;
    };

    @Override
    public String title() {
        return "Market Maker - Java";
    }

    @Override
    public MarketMakerJavaParams jDecodeParams(String paramsStr) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(paramsStr, MarketMakerJavaParams.class);
    }

    @Override
    public CompletableFuture<StrategyInfo> jInfo(SessionLoader loader) {
        infoLoader = loader;
        ObjectMapper mapper = new ObjectMapper();
        JsonSchemaConfig config = JsonSchemaConfig.create(false, Optional.empty(), true, true, false,
                false, false, false, false, new HashMap<>(), false, new HashSet<>(), new HashMap<>(),
                new HashMap<String, Supplier<JsonNode>>() {
                    {
                        put("market", marketSupplier);
                    }
                });

        JsonSchemaGenerator schemaGen = new JsonSchemaGenerator(mapper, config);
        JsonNode schema = schemaGen.generateJsonSchema(MarketMakerJavaParams.class);

        String schemaStr = null;
        try {
            schemaStr = mapper.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return CompletableFuture.completedFuture(new StrategyInfo(schemaStr));
    }

    @Override
    public CompletableFuture<List<DataPath<Object>>> jInitialize(Portfolio portfolio, SessionLoader loader) {
//        return CompletableFuture.completedFuture(Arrays.asList(
//                new DataPath<>("", "", DataType.apply("trades")),
//                new DataPath<>(params().exchange, params()., DataType.apply("candles_1m"))
//        ));
        return null;
    }

    @Override
    public void jHandleData(MarketData<?> data, TradingSession ctx) {
    }

    @Override
    public void jHandleEvent(StrategyEvent event, TradingSession ctx) {
    }
}
