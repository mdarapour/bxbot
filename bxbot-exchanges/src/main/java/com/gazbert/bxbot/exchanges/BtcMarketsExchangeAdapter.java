package com.gazbert.bxbot.exchanges;

import com.gazbert.bxbot.exchange.api.AuthenticationConfig;
import com.gazbert.bxbot.exchange.api.ExchangeAdapter;
import com.gazbert.bxbot.exchange.api.ExchangeConfig;
import com.gazbert.bxbot.exchange.api.OptionalConfig;
import com.gazbert.bxbot.exchanges.trading.api.impl.MarketOrderBookImpl;
import com.gazbert.bxbot.exchanges.trading.api.impl.MarketOrderImpl;
import com.gazbert.bxbot.exchanges.trading.api.impl.OpenOrderImpl;
import com.gazbert.bxbot.exchanges.trading.api.impl.TickerImpl;
import com.gazbert.bxbot.trading.api.*;
import com.google.common.base.MoreObjects;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.util.SystemClock;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BinaryOperator;


/**
 * <p>
 * Exchange Adapter for integrating with the BTCMarkets exchange.
 * The BTCMarkets API is documented <a href="https://github.com/BTCMarkets/API">here</a>.
 * </p>
 * <p>
 * The {@link TradingApi} calls will throw a {@link ExchangeNetworkException} if a network error occurs trying to
 * connect to the exchange. A {@link TradingApiException} is thrown for <em>all</em> other failures.
 * </p>
 *
 * @author mdarapour
 * @since 1.0
 */
public class BtcMarketsExchangeAdapter extends AbstractExchangeAdapter implements ExchangeAdapter {

    private static final Logger LOG = LogManager.getLogger();

    /**
     * The public API URI.
     */
    private static final String PUBLIC_API_BASE_URL = "https://api.btcmarkets.net/";

    /**
     * Used for reporting unexpected errors.
     */
    private static final String UNEXPECTED_ERROR_MSG = "Unexpected error has occurred in BTC Markets Exchange Adapter. ";

    /**
     * Unexpected IO error message for logging.
     */
    private static final String UNEXPECTED_IO_ERROR_MSG = "Failed to connect to Exchange due to unexpected IO error.";

    /**
     * Name of PUBLIC key prop in config file.
     */
    private static final String KEY_PROPERTY_NAME = "key";

    /**
     * Name of secret prop in config file.
     */
    private static final String SECRET_PROPERTY_NAME = "secret";

    /**
     * Name of buy fee property in config file.
     */
    private static final String BUY_FEE_PROPERTY_NAME = "buy-fee";

    /**
     * Name of sell fee property in config file.
     */
    private static final String SELL_FEE_PROPERTY_NAME = "sell-fee";

    /**
     * Name of order type property in config file.
     */
    private static final String ORDER_TYPE_PROPERTY_NAME = "order-type";

    /**
     * Name of orders limit property in config file.
     */
    private static final String ORDERS_LIMIT_PROPERTY_NAME = "orders-limit";

    /**
     * Name of orders since property in config file.
     */
    private static final String ORDERS_SINCE_PROPERTY_NAME = "orders-since";

    /**
     * Encryption algorithm used for signing requests
     */
    private static final String ALGORITHM = "HmacSHA512";

    /**
     * Character set encoding
     */
    private static final String ENCODING = "UTF-8";

    /**
     * Signature HTTP Header
     */
    private static final String SIGNATURE_HEADER = "signature";

    /**
     * API Key HTTP Header
     */
    private static final String APIKEY_HEADER = "apikey";

    /**
     * Timestamp HTTP Header
     */
    private static final String TIMESTAMP_HEADER = "timestamp";

    /**
     * Exchange buy fees in % in {@link BigDecimal} format.
     */
    private BigDecimal buyFeePercentage;

    /**
     * Exchange sell fees in % in {@link BigDecimal} format.
     */
    private BigDecimal sellFeePercentage;

    /**
     * Default order type
     */
    private String orderType;

    /**
     * Default orders limit
     */
    private int ordersLimit;

    /**
     * calculated timestamp based on {@link #ORDERS_SINCE_PROPERTY_NAME}
     */
    private long ordersSince;

    /**
     * Used to indicate if we have initialised the authentication and secure messaging layer.
     */
    private boolean initializedSecureMessagingLayer = false;

    /**
     * Base currency which is set in market configuration.
     */
    private String baseCurrency;

    /**
     * Counter currency value which is set in market configuration.
     */
    private String counterCurrency;

    /**
     * The key used in the secure message.
     */
    private String key = "";

    /**
     * The secret used for signing secure message.
     */
    private String secret = "";

    /**
     * The Message Digest generator used by the secure messaging layer.
     * Used to create the hash of the entire message with the private key to ensure message integrity.
     */
    private MessageDigest messageDigest;

    /**
     * GSON engine used for parsing JSON in RtcMarkets API call responses.
     */
    private Gson gson;

    /**
     * BTCMarkets API Methods
     */
    enum ApiMethod {
        CREATE_ORDER("/order/create"),
        CANCEL_ORDER("/order/cancel"),
        ORDERBOOK("/market/%s/orderbook"),
        OPEN_ORDERS("/order/open"),
        TICK("/market/%s/tick");

        ApiMethod(String context) {
            this.context = context;
        }

        private String context;

        public String getMethod(String instrument) {
            return String.format(context, instrument);
        }
    }

    enum MarketConfig {
        BTC_AUD("btcaud"),
        LTC_AUD("ltcaud"),
        ETH_AUD("ethaud"),
        ETC_AUD("etcaud"),
        XRP_AUD("xrpaud"),
        BCH_AUD("bchaud"),
        LTC_BTC("ltcbtc"),
        ETH_BTC("ethbtc"),
        ETC_BTC("etcbtc"),
        XRP_BTC("xrpbtc"),
        BCH_BTC("bchbtc");

        MarketConfig(String marketId) {
            this.marketId = marketId;
        }

        private String marketId;

        public String getMarketId() {
            return marketId;
        }

        public String getBaseCurrency() {
            return name().substring(0,3);
        }

        public String getCounterCurrency() {
            return name().substring(4);
        }

        public String getInstrument() {
            return name().replace("_", "/");
        }

        public static MarketConfig configOf(String marketId) {
            Optional<MarketConfig> marketConfig = Arrays.stream(MarketConfig.values()).filter((m) -> m.marketId.equals(marketId)).findFirst();
            if(marketConfig.isPresent()) {
                return marketConfig.get();
            } else {
                throw new NoSuchElementException(String.format("Market ID [%s] not found", marketId));
            }
        }
    }

    @Override
    public void init(ExchangeConfig config) {
        LOG.info(() -> "About to initialise BtcMarket ExchangeConfig: " + config);
        setAuthenticationConfig(config);
        setNetworkConfig(config);
        setOptionalConfig(config);

        initSecureMessageLayer();
        initGson();
    }

    @Override
    public String getImplName() {
        return "BTC Markets Developer API";
    }

    @Override
    public MarketOrderBook getMarketOrders(String marketId) throws ExchangeNetworkException, TradingApiException {
        try {

            final String apiMethod = ApiMethod.ORDERBOOK.getMethod(MarketConfig.configOf(marketId).getInstrument());
            final ExchangeHttpResponse response = sendPublicRequestToExchange(HttpMethod.GET, apiMethod, null, null)    ;
            LOG.debug(() -> "Market Orders response: " + response);

            final BtcMarketsOrderbook orderBook = gson.fromJson(response.getPayload(), BtcMarketsOrderbook.class);

            final List<MarketOrder> buyOrders = new ArrayList<>();
            for (BtcMarketsMarketOrder okCoinBuyOrder : orderBook.bids) {
                final MarketOrder buyOrder = new MarketOrderImpl(
                        OrderType.BUY,
                        okCoinBuyOrder.get(0),
                        okCoinBuyOrder.get(1),
                        okCoinBuyOrder.get(0).multiply(okCoinBuyOrder.get(1)));
                buyOrders.add(buyOrder);
            }

            final List<MarketOrder> sellOrders = new ArrayList<>();
            for (BtcMarketsMarketOrder okCoinSellOrder : orderBook.asks) {
                final MarketOrder sellOrder = new MarketOrderImpl(
                        OrderType.SELL,
                        okCoinSellOrder.get(0),
                        okCoinSellOrder.get(1),
                        okCoinSellOrder.get(0).multiply(okCoinSellOrder.get(1)));
                sellOrders.add(sellOrder);
            }

            // For some reason, BTC Markets sorts ask orders in descending order instead of ascending.
            // We need to re-order price ascending - lowest ASK price will be first in list.
            sellOrders.sort((thisOrder, thatOrder) -> {
                if (thisOrder.getPrice().compareTo(thatOrder.getPrice()) < 0) {
                    return -1;
                } else if (thisOrder.getPrice().compareTo(thatOrder.getPrice()) > 0) {
                    return 1;
                } else {
                    return 0; // same price
                }
            });

            return new MarketOrderBookImpl(marketId, sellOrders, buyOrders);

        } catch (ExchangeNetworkException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public List<OpenOrder> getYourOpenOrders(String marketId) throws ExchangeNetworkException, TradingApiException {
        try {
            final String apiMethod = ApiMethod.OPEN_ORDERS.getMethod(MarketConfig.configOf(marketId).getInstrument());
            final BtcMarketsOpenOrdersRequest openOrdersRequest = new BtcMarketsOpenOrdersRequest();
            openOrdersRequest.currency =  MarketConfig.configOf(marketId).getCounterCurrency();
            openOrdersRequest.instrument = MarketConfig.configOf(marketId).getBaseCurrency();
            openOrdersRequest.limit = ordersLimit;
            openOrdersRequest.since = ordersSince;

            final ExchangeHttpResponse response = sendPublicRequestToExchange(HttpMethod.POST, apiMethod, gson.toJson(openOrdersRequest), null);
            LOG.debug(() -> "Open Orders response: " + response);

            final BtcMarketsOrdersWrapper openOrders = gson.fromJson(response.getPayload(), BtcMarketsOrdersWrapper.class);
            if (openOrders.success) {

                final List<OpenOrder> ordersToReturn = new ArrayList<>();
                for (final BtcMarketsOrder openOrder : openOrders.orders) {
                    OrderType orderType;
                    switch (openOrder.orderSide) {
                        case "Bid":
                            orderType = OrderType.BUY;
                            break;
                        case "Ask":
                            orderType = OrderType.SELL;
                            break;
                        default:
                            throw new TradingApiException(
                                    "Unrecognised order type received in getYourOpenOrders(). Value: " + openOrder.orderSide);
                    }

                    final OpenOrder order = new OpenOrderImpl(
                            String.valueOf(openOrder.id),
                            new Date(openOrder.creationTime),
                            marketId,
                            orderType,
                            openOrder.price,
                            openOrder.volume,
                            openOrder.openVolume,
                            openOrder.price.multiply(openOrder.volume) // total - not provided by BtcMarkets :-(
                    );

                    ordersToReturn.add(order);
                }
                return ordersToReturn;

            } else {
                final String errorMsg = "Failed to get Open Order Info from exchange. Error response: " + response;
                LOG.error(errorMsg);
                throw new TradingApiException(errorMsg);
            }

        } catch (ExchangeNetworkException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public String createOrder(String marketId, OrderType orderType, BigDecimal quantity, BigDecimal price) throws ExchangeNetworkException, TradingApiException {
        try {

            final BtcMarketsCreateOrderRequest createOrderRequest = new BtcMarketsCreateOrderRequest();
            final MarketConfig marketConfig = MarketConfig.configOf(marketId);

            if (orderType == OrderType.BUY) {
                createOrderRequest.orderSide = "Bid";
            } else if (orderType == OrderType.SELL) {
                createOrderRequest.orderSide = "Ask";
            } else {
                final String errorMsg = "Invalid order type: " + orderType
                        + " - Can only be "
                        + OrderType.BUY.getStringValue() + " or "
                        + OrderType.SELL.getStringValue();
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            createOrderRequest.ordertype = this.orderType;
            createOrderRequest.currency = marketConfig.getCounterCurrency();
            createOrderRequest.instrument = marketConfig.getBaseCurrency();
            createOrderRequest.price = price;
            createOrderRequest.volume = quantity;

            String apiMethod = ApiMethod.CREATE_ORDER.getMethod(MarketConfig.configOf(marketId).getInstrument());
            final ExchangeHttpResponse response = sendPublicRequestToExchange(HttpMethod.POST, apiMethod, gson.toJson(createOrderRequest), null);
            LOG.debug(() -> "Create Order response: " + response);

            final BtcMarketTradeResponse createOrderResponse = gson.fromJson(response.getPayload(), BtcMarketTradeResponse.class);
            if (createOrderResponse.success) {
                return Long.toString(createOrderResponse.id);
            } else {
                final String errorMsg = "Failed to place order on exchange. Error response: " + response;
                LOG.error(errorMsg);
                throw new TradingApiException(errorMsg);
            }

        } catch (ExchangeNetworkException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public boolean cancelOrder(String orderId, String marketId) throws ExchangeNetworkException, TradingApiException {
        try {

            BtcMarketsCancelOrderRequest cancelOrderRequest = new BtcMarketsCancelOrderRequest(Lists.newArrayList(orderId));

            String apiMethod = ApiMethod.CANCEL_ORDER.getMethod(MarketConfig.configOf(marketId).getInstrument());
            final ExchangeHttpResponse response = sendPublicRequestToExchange(HttpMethod.POST, apiMethod, gson.toJson(cancelOrderRequest), null);
            LOG.debug(() -> "Cancel Order response: " + response);

            final BtcMarketsCancelOrderResponses cancelOrderResponse = gson.fromJson(response.getPayload(), BtcMarketsCancelOrderResponses.class);
            if (cancelOrderResponse.success) {
                return true;
            } else {
                final String errorMsg = "Failed to cancel order on exchange. Error response: " + response;
                LOG.error(errorMsg);
                return false;
            }

        } catch (ExchangeNetworkException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public BigDecimal getLatestMarketPrice(String marketId) throws ExchangeNetworkException, TradingApiException {
        try {
            String apiMethod = ApiMethod.TICK.getMethod(MarketConfig.configOf(marketId).getInstrument());
            final ExchangeHttpResponse response = sendPublicRequestToExchange(HttpMethod.GET, apiMethod, null, null);
            LOG.debug(() -> "Latest Market Price response: " + response);

            final BtcMarketsTick tick = gson.fromJson(response.getPayload(), BtcMarketsTick.class);
            return tick.lastPrice;

        } catch (ExchangeNetworkException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public BalanceInfo getBalanceInfo() throws ExchangeNetworkException, TradingApiException {
        return null;
    }

    @Override
    public BigDecimal getPercentageOfBuyOrderTakenForExchangeFee(String marketId) throws TradingApiException, ExchangeNetworkException {
        return null;
    }

    @Override
    public BigDecimal getPercentageOfSellOrderTakenForExchangeFee(String marketId) throws TradingApiException, ExchangeNetworkException {
        return null;
    }

    @Override
    public Ticker getTicker(String marketId) throws TradingApiException, ExchangeNetworkException {
        try {
            String apiMethod = ApiMethod.TICK.getMethod(MarketConfig.configOf(marketId).getInstrument());
            final ExchangeHttpResponse response = sendPublicRequestToExchange(HttpMethod.GET, apiMethod, null, null);
            LOG.debug(() -> "Latest Market Price response: " + response);

            final BtcMarketsTick tick = gson.fromJson(response.getPayload(), BtcMarketsTick.class);
            return new TickerImpl(
                    tick.lastPrice,
                    tick.bestBid,
                    tick.bestAsk,
                    null, // low not supplied by BtcMarkets
                    null, // high not supplied by BtcMarkets
                    null, // open not supplied by BtcMarkets
                    tick.volume24h,
                    null, // vwap not supplied by BtcMarkets
                    tick.timestamp);

        } catch (ExchangeNetworkException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    // ------------------------------------------------------------------------------------------------
    //  GSON classes for JSON requests.
    // ------------------------------------------------------------------------------------------------

    public static class BtcMarketsBaseRequest {
        public String currency;
        public String instrument;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("currency", currency)
                    .add("instrument", instrument)
                    .toString();
        }
    }

    public static class BtcMarketsCancelOrderRequest {
        public ArrayList<String> orderIds;

        public BtcMarketsCancelOrderRequest(ArrayList<String> orderIds) {
            this.orderIds = orderIds;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("orderIds", orderIds)
                    .toString();
        }
    }

    public static class BtcMarketsCreateOrderRequest extends BtcMarketsBaseRequest {
        public BigDecimal price;
        public BigDecimal volume;
        public String orderSide;
        public String ordertype;
        public String clientRequestId;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("price", price)
                    .add("volume", volume)
                    .add("orderSide", orderSide)
                    .add("ordertype", ordertype)
                    .add("clientRequestId", clientRequestId)
                    .toString();
        }
    }

    public static class BtcMarketsOpenOrdersRequest extends BtcMarketsBaseRequest {
        public int limit;
        public long since;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("limit", limit)
                    .add("since", since)
                    .toString();
        }
    }

    // ------------------------------------------------------------------------------------------------
    //  GSON classes for JSON responses.
    // ------------------------------------------------------------------------------------------------

    /**
     * GSON base class for API call requests and responses.
     */
    private static class BtcMarketsMessageBase {

        public boolean success; // will be JSON boolean value in response: true or false
        public String errorCode;
        public String errorMessage;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("success", success)
                    .add("errorCode", errorCode)
                    .add("errorMessage", errorMessage)
                    .toString();
        }
    }

    /**
     * GSON base class for response.
     */
    public static class BtcMarketsEntityResponse extends BtcMarketsMessageBase {

        public String id;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("id", id)
                    .toString();
        }
    }

    /**
     * GSON class for wrapping '/order/cancel' response.
     */
    public static class BtcMarketsCancelOrderResponse extends BtcMarketsEntityResponse {

    }

    public static class BtcMarketsCancelOrderResponses extends BtcMarketsMessageBase {
        public ArrayList<BtcMarketsCancelOrderResponse> responses;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("responses=", responses)
                    .toString();
        }
    }

    /**
     * GSON class for wrapping '/order/create' response.
     */
    public static class BtcMarketTradeResponse extends BtcMarketsMessageBase {

        public long id;
        public String clientRequestId;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("id", id)
                    .add("clientRequestId", clientRequestId)
                    .toString();
        }
    }

    /**
     * GSON wrapper class for '/order/open`
     */
    public static class BtcMarketsOrdersWrapper extends BtcMarketsMessageBase {
        public ArrayList<BtcMarketsOrder> orders;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("orders", orders)
                    .toString();
        }
    }

    /**
     * GSON class for Open Order
     */
    public static class BtcMarketsOrder extends BtcMarketTradeResponse {
        public String currency;
        public String instrument;
        public String orderSide;
        public String orderType;
        public long creationTime;
        public String status;
        public BigDecimal price;
        public BigDecimal volume;
        public BigDecimal openVolume;
        public ArrayList<BtcMarketsTrade> trades;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("currency", currency)
                    .add("instrument", instrument)
                    .add("orderSide", orderSide)
                    .add("orderType", orderType)
                    .add("creationTime", creationTime)
                    .add("status", status)
                    .add("price", price)
                    .add("volume", volume)
                    .add("openVolume", openVolume)
                    .add("trades", trades)
                    .toString();
        }
    }

    /**
     * GSON class for trade
     */
    public static class BtcMarketsTrade extends BtcMarketsEntityResponse {
        public long creationTime;
        public String description;
        public BigDecimal price;
        public BigDecimal volume;
        public BigDecimal fee;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("creationTime", creationTime)
                    .add("description", description)
                    .add("price", price)
                    .add("volume", volume)
                    .add("fee", fee)
                    .toString();
        }
    }

    /**
     * GSON class for orderbook response.
     */
    private static class BtcMarketsOrderbook {
        public String currency;
        public String instrument;
        public long timestamp;
        public List<BtcMarketsMarketOrder> asks;
        public List<BtcMarketsMarketOrder> bids;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("currency", currency)
                    .add("instrument", instrument)
                    .add("timestamp", timestamp)
                    .add("asks", asks)
                    .add("bids", bids)
                    .toString();
        }
    }

    /**
     * GSON class for holding Market Orders. First element in array is price, second element is amount.
     */
    private static class BtcMarketsMarketOrder extends ArrayList<BigDecimal> {
    }

    /**
     * GSON class for a BTCMarkets tick response.
     */
    private static class BtcMarketsTick {

        public BigDecimal bestBid;
        public BigDecimal bestAsk;
        public BigDecimal lastPrice;
        public String currency;
        public String instrument;
        public long timestamp;
        public BigDecimal volume24h;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("bestBid", bestBid)
                    .add("bestAsk", bestAsk)
                    .add("lastPrice", lastPrice)
                    .add("currency", currency)
                    .add("instrument", instrument)
                    .add("timestamp", timestamp)
                    .add("volume24h", volume24h)
                    .toString();
        }
    }

    // ------------------------------------------------------------------------------------------------
    //  Transport layer methods
    // ------------------------------------------------------------------------------------------------

    /**
     * Makes a public API call to the BTC Markets exchange.
     *
     * @param apiMethod the API method to call.
     * @param params    the query param args to use in the API call
     * @return the response from the exchange.
     * @throws ExchangeNetworkException if there is a network issue connecting to exchange.
     * @throws TradingApiException      if anything unexpected happens.
     */
    private ExchangeHttpResponse sendPublicRequestToExchange(HttpMethod httpMethod,
                                                             String apiMethod,
                                                             String postData,
                                                             Map<String, String> params) throws ExchangeNetworkException, TradingApiException {

        if (params == null) {
            params = createRequestParamMap(); // no params, so empty query string
        }

        final Map<String, String> requestHeaders = createHeaderParamMap();

        try {

            final StringBuilder queryString = new StringBuilder();
            if (!params.isEmpty()) {
                queryString.append("?");
                for (final Map.Entry<String, String> param : params.entrySet()) {
                    if (queryString.length() > 1) {
                        queryString.append("&");
                    }
                    //noinspection deprecation
                    queryString.append(param.getKey());
                    queryString.append("=");
                    queryString.append(URLEncoder.encode(param.getValue(), ENCODING));
                }
            }

            //get the current timestamp. It's best to use ntp or similar services in order to sync your server time
            String timestamp = Long.toString(System.currentTimeMillis());

            // create the string that needs to be signed
            String stringToSign = buildStringToSign(apiMethod, queryString.toString(), postData, timestamp);

            // build signature to be included in the http header
            String signature = signRequest(secret, stringToSign);

            requestHeaders.put("Accept", "*/*");
            requestHeaders.put("Content-Type", "application/json");
            requestHeaders.put("Accept-Charset", ENCODING);

            // Add signature, timestamp and apiKey to the http header
            requestHeaders.put(SIGNATURE_HEADER, signature);
            requestHeaders.put(APIKEY_HEADER, key);
            requestHeaders.put(TIMESTAMP_HEADER, timestamp);

            final URL url = new URL(PUBLIC_API_BASE_URL + apiMethod + queryString);
            return makeNetworkRequest(url, httpMethod.name(), postData, requestHeaders);

        } catch (MalformedURLException | UnsupportedEncodingException e) {
            final String errorMsg = UNEXPECTED_IO_ERROR_MSG;
            LOG.error(errorMsg, e);
            throw new TradingApiException(errorMsg, e);
        }
    }

    private String buildStringToSign(String uri,
                                     String queryString,
                                     String postData,
                                     String timestamp) {
        // queryString must be sorted key=value& pairs
        StringBuilder stringToSign = new StringBuilder();
        stringToSign.append(uri).append("\n");
        if (queryString != null) {
            stringToSign.append(queryString).append("\n");
        }
        stringToSign.append(timestamp).append("\n");
        stringToSign.append(postData);
        return stringToSign.toString();
    }

    private String signRequest(String secret, String data) throws TradingApiException {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec secret_spec = new SecretKeySpec(Base64.getDecoder().decode(secret), ALGORITHM);
            mac.init(secret_spec);
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes()));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new TradingApiException(e.getMessage(), e);
        }
    }

    /**
     * Initialises the secure messaging layer
     * Sets up the Message Digest to safeguard the data we send to the exchange.
     * We fail hard n fast if any of this stuff blows.
     */
    private void initSecureMessageLayer() {

        try {
            messageDigest = MessageDigest.getInstance("MD5");
            initializedSecureMessagingLayer = true;
        } catch (NoSuchAlgorithmException e) {
            final String errorMsg = "Failed to setup MessageDigest for secure message layer. Details: " + e.getMessage();
            LOG.error(errorMsg, e);
            throw new IllegalStateException(errorMsg, e);
        }
    }

    // ------------------------------------------------------------------------------------------------
    //  Config methods
    // ------------------------------------------------------------------------------------------------

    private void setAuthenticationConfig(ExchangeConfig exchangeConfig) {

        final AuthenticationConfig authenticationConfig = getAuthenticationConfig(exchangeConfig);
        key = getAuthenticationConfigItem(authenticationConfig, KEY_PROPERTY_NAME);
        secret = getAuthenticationConfigItem(authenticationConfig, SECRET_PROPERTY_NAME);
    }

    private void setOptionalConfig(ExchangeConfig exchangeConfig) {

        final OptionalConfig optionalConfig = getOptionalConfig(exchangeConfig);

        final String buyFeeInConfig = getOptionalConfigItem(optionalConfig, BUY_FEE_PROPERTY_NAME);
        buyFeePercentage = new BigDecimal(buyFeeInConfig).divide(new BigDecimal("100"), 8, BigDecimal.ROUND_HALF_UP);
        LOG.info(() -> "Buy fee % in BigDecimal format: " + buyFeePercentage);

        final String sellFeeInConfig = getOptionalConfigItem(optionalConfig, SELL_FEE_PROPERTY_NAME);
        sellFeePercentage = new BigDecimal(sellFeeInConfig).divide(new BigDecimal("100"), 8, BigDecimal.ROUND_HALF_UP);
        LOG.info(() -> "Sell fee % in BigDecimal format: " + sellFeePercentage);

        orderType = getOptionalConfigItem(optionalConfig, ORDER_TYPE_PROPERTY_NAME);
        ordersLimit = new Integer(getOptionalConfigItem(optionalConfig, ORDERS_LIMIT_PROPERTY_NAME));

        final long hours = Integer.parseInt(getOptionalConfigItem(optionalConfig, ORDERS_SINCE_PROPERTY_NAME));
        ordersSince = Instant.now(createClock()).minus(hours, ChronoUnit.HOURS).toEpochMilli();
    }

    // ------------------------------------------------------------------------------------------------
    //  Util methods
    // ------------------------------------------------------------------------------------------------

    /**
     * Initialises the GSON layer.
     */
    private void initGson() {
        final GsonBuilder gsonBuilder = new GsonBuilder();
        gson = gsonBuilder.create();
    }

    /*
     * Hack for unit-testing map params passed to transport layer.
     */
    private Map<String, String> createRequestParamMap() {
        return new HashMap<>();
    }

    /*
     * Hack for unit-testing header params passed to transport layer.
     */
    private Map<String, String> createHeaderParamMap() {
        return new HashMap<>();
    }

    /**
     * Hack for unit-testing timestamp
     * @return Clock
     */
    private Clock createClock() {
        return Clock.systemUTC();
    }

    /*
     * Hack for unit-testing transport layer.
     */
    private ExchangeHttpResponse makeNetworkRequest(URL url, String httpMethod, String postData, Map<String, String> requestHeaders)
            throws TradingApiException, ExchangeNetworkException {
        return super.sendNetworkRequest(url, httpMethod, postData, requestHeaders);
    }
}
