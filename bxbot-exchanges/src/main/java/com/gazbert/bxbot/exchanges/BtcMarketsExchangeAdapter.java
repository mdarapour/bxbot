package com.gazbert.bxbot.exchanges;

import com.gazbert.bxbot.exchange.api.AuthenticationConfig;
import com.gazbert.bxbot.exchange.api.ExchangeAdapter;
import com.gazbert.bxbot.exchange.api.ExchangeConfig;
import com.gazbert.bxbot.exchange.api.OptionalConfig;
import com.gazbert.bxbot.exchanges.trading.api.impl.MarketOrderBookImpl;
import com.gazbert.bxbot.exchanges.trading.api.impl.MarketOrderImpl;
import com.gazbert.bxbot.trading.api.*;
import com.google.common.base.MoreObjects;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * Exchange buy fees in % in {@link BigDecimal} format.
     */
    private BigDecimal buyFeePercentage;

    /**
     * Exchange sell fees in % in {@link BigDecimal} format.
     */
    private BigDecimal sellFeePercentage;

    /**
     * Used to indicate if we have initialised the authentication and secure messaging layer.
     */
    private boolean initializedSecureMessagingLayer = false;

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

            final Map<String, String> params = createRequestParamMap();
            params.put("symbol", marketId);

            final ExchangeHttpResponse response = sendPublicRequestToExchange(HttpMethod.GET,"/market/" + marketId + "/orderbook", null, params)    ;
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
        return null;
    }

    @Override
    public String createOrder(String marketId, OrderType orderType, BigDecimal quantity, BigDecimal price) throws ExchangeNetworkException, TradingApiException {
        return null;
    }

    @Override
    public boolean cancelOrder(String orderId, String marketId) throws ExchangeNetworkException, TradingApiException {
        return false;
    }

    @Override
    public BigDecimal getLatestMarketPrice(String marketId) throws ExchangeNetworkException, TradingApiException {
        return null;
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

    // ------------------------------------------------------------------------------------------------
    //  GSON classes for JSON responses.
    // ------------------------------------------------------------------------------------------------

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
                                                             Map<String, String> params) throws
            ExchangeNetworkException, TradingApiException {

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
                    queryString.append(URLEncoder.encode(param.getValue(), "UTF-8"));
                }

                requestHeaders.put("Content-Type", "application/json");
            }

            final URL url = new URL(PUBLIC_API_BASE_URL + apiMethod + queryString);
            return makeNetworkRequest(url, httpMethod.name(), postData, requestHeaders);

        } catch (MalformedURLException | UnsupportedEncodingException e) {
            final String errorMsg = UNEXPECTED_IO_ERROR_MSG;
            LOG.error(errorMsg, e);
            throw new TradingApiException(errorMsg, e);
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

    /*
     * Hack for unit-testing transport layer.
     */
    private ExchangeHttpResponse makeNetworkRequest(URL url, String httpMethod, String postData, Map<String, String> requestHeaders)
            throws TradingApiException, ExchangeNetworkException {
        return super.sendNetworkRequest(url, httpMethod, postData, requestHeaders);
    }
}
