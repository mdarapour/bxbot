package com.gazbert.bxbot.exchanges;

import com.gazbert.bxbot.exchange.api.AuthenticationConfig;
import com.gazbert.bxbot.exchange.api.ExchangeConfig;
import com.gazbert.bxbot.exchange.api.NetworkConfig;
import com.gazbert.bxbot.exchange.api.OptionalConfig;
import com.gazbert.bxbot.trading.api.*;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.easymock.PowerMock;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.easymock.EasyMock.*;
import static org.junit.Assert.*;

/**
 * Tests the behaviour of the BtcMarkets Exchange Adapter.
 *
 * @author mdarapour
 */
@RunWith(PowerMockRunner.class)
@PowerMockIgnore({"javax.crypto.*", "javax.management.*"})
@PrepareForTest(BtcMarketsExchangeAdapter.class)
public class TestBtcMarketsExchangeAdapter {

    // Canned JSON responses from exchange - expected to reside on filesystem relative to project root
    private static final String ORDERBOOK_JSON_RESPONSE = "./src/test/exchange-data/btcmarkets/orderbook.json";
    private static final String TICK_JSON_RESPONSE = "./src/test/exchange-data/btcmarkets/ticker.json";
    private static final String CANCEL_ORDER_JSON_RESPONSE = "./src/test/exchange-data/btcmarkets/cancel_order.json";
    private static final String USERINFO_JSON_RESPONSE = "./src/test/exchange-data/btcmarkets/userinfo.json";
    private static final String USERINFO_ERROR_JSON_RESPONSE = "./src/test/exchange-data/btcmarkets/userinfo-error.json";
    private static final String ORDER_INFO_JSON_RESPONSE = "./src/test/exchange-data/btcmarkets/order_info.json";
    private static final String ORDER_INFO_ERROR_JSON_RESPONSE = "./src/test/exchange-data/btcmarkets/order_info-error.json";
    private static final String CREATE_ORDER_SUCCESS_JSON_RESPONSE = "./src/test/exchange-data/btcmarkets/create_oder_success.json";
    private static final String CREATE_ORDER_FAIL_JSON_RESPONSE = "./src/test/exchange-data/btcmarkets/create_order_fail.json";
    private static final String TRADE_ERROR_JSON_RESPONSE = "./src/test/exchange-data/btcmarkets/trade-error.json";
    private static final String CANCEL_ORDER_ERROR_JSON_RESPONSE = "./src/test/exchange-data/btcmarkets/cancel_order-error.json";

    // Canned test data
    private static final String MARKET_ID = "btcaud";
    private static final String MARKET_NAME = "BTC/AUD";
    private static final BigDecimal BUY_ORDER_PRICE = new BigDecimal("200.18");
    private static final BigDecimal BUY_ORDER_QUANTITY = new BigDecimal("0.01");
    private static final BigDecimal SELL_ORDER_PRICE = new BigDecimal("300.176");
    private static final BigDecimal SELL_ORDER_QUANTITY = new BigDecimal("0.01");
    private static final String ORDER_ID_TO_CANCEL = "99671870";

    // Exchange API calls
    private static final String ORDERBOOK = "/market/"+ MARKET_NAME +"/orderbook";
    private static final String ORDER_INFO = "order_info.do";
    private static final String USERINFO = "userinfo.do";
    private static final String TICK = "/market/"+ MARKET_NAME +"/tick";
    private static final String CREATE_ORDER = "/order/create";
    private static final String CANCEL_ORDER = "/order/cancel";

    // Mocked out methods
    private static final String MOCKED_CREATE_REQUEST_PARAM_MAP_METHOD = "createRequestParamMap";
    private static final String MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD = "sendPublicRequestToExchange";
    private static final String MOCKED_CREATE_REQUEST_HEADER_MAP_METHOD = "createHeaderParamMap";
    private static final String MOCKED_MAKE_NETWORK_REQUEST_METHOD = "makeNetworkRequest";

    // Exchange Adapter config for the tests
    private static final String KEY = "key123";
    private static final String SECRET = "notGonnaTellYa";
    private static final List<Integer> nonFatalNetworkErrorCodes = Arrays.asList(502, 503, 504);
    private static final List<String> nonFatalNetworkErrorMessages = Arrays.asList(
            "Connection refused", "Connection reset", "Remote host closed connection during handshake");

    private static final String OKCOIN_API_VERSION = "v1";
    private static final String PUBLIC_API_BASE_URL = "https://api.btcmarkets.net/";
    private static final String AUTHENTICATED_API_URL = PUBLIC_API_BASE_URL;

    private ExchangeConfig exchangeConfig;
    private AuthenticationConfig authenticationConfig;
    private NetworkConfig networkConfig;
    private OptionalConfig optionalConfig;
    private Gson gson;

    @Before
    public void setup() {
        authenticationConfig = PowerMock.createMock(AuthenticationConfig.class);
        expect(authenticationConfig.getItem("key")).andReturn(KEY);
        expect(authenticationConfig.getItem("secret")).andReturn(SECRET);

        networkConfig = PowerMock.createMock(NetworkConfig.class);
        expect(networkConfig.getConnectionTimeout()).andReturn(30);
        expect(networkConfig.getNonFatalErrorCodes()).andReturn(nonFatalNetworkErrorCodes);
        expect(networkConfig.getNonFatalErrorMessages()).andReturn(nonFatalNetworkErrorMessages);

        optionalConfig = PowerMock.createMock(OptionalConfig.class);
        expect(optionalConfig.getItem("buy-fee")).andReturn("0.2");
        expect(optionalConfig.getItem("sell-fee")).andReturn("0.2");
        expect(optionalConfig.getItem("order-type")).andReturn("Market");

        exchangeConfig = PowerMock.createMock(ExchangeConfig.class);
        expect(exchangeConfig.getAuthenticationConfig()).andReturn(authenticationConfig);
        expect(exchangeConfig.getNetworkConfig()).andReturn(networkConfig);
        expect(exchangeConfig.getOptionalConfig()).andReturn(optionalConfig);

        gson = new GsonBuilder().create();
    }

    // ------------------------------------------------------------------------------------------------
    //  Cancel Order tests
    // ------------------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    public void testCancelOrderIsSuccessful() throws Exception {

        // Load the canned response from the exchange
        final byte[] encoded = Files.readAllBytes(Paths.get(CANCEL_ORDER_JSON_RESPONSE));
        final AbstractExchangeAdapter.ExchangeHttpResponse exchangeResponse =
                new AbstractExchangeAdapter.ExchangeHttpResponse(200, "OK", new String(encoded, StandardCharsets.UTF_8));

        // Mock out post data so we can assert the contents passed to the transport layer are what we expect.
        final BtcMarketsExchangeAdapter.BtcMarketsCancelOrderRequest requestObject = new BtcMarketsExchangeAdapter.BtcMarketsCancelOrderRequest(Lists.newArrayList(ORDER_ID_TO_CANCEL));

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);

        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                eq(TradingApi.HttpMethod.POST),
                eq(CANCEL_ORDER),
                eq(gson.toJson(requestObject)),
                anyObject(Map.class)).andReturn(exchangeResponse);

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        final boolean success = exchangeAdapter.cancelOrder(ORDER_ID_TO_CANCEL, MARKET_ID);
        assertTrue(success);

        PowerMock.verifyAll();
    }

    @Test
    public void testCancelOrderExchangeErrorResponse() throws Exception {

        // Load the canned response from the exchange
        final byte[] encoded = Files.readAllBytes(Paths.get(CANCEL_ORDER_ERROR_JSON_RESPONSE));
        final AbstractExchangeAdapter.ExchangeHttpResponse exchangeResponse =
                new AbstractExchangeAdapter.ExchangeHttpResponse(200, "OK", new String(encoded, StandardCharsets.UTF_8));

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);
        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                eq(TradingApi.HttpMethod.POST),
                eq(CANCEL_ORDER),
                anyObject(BtcMarketsExchangeAdapter.BtcMarketsCancelOrderRequest.class),
                anyObject(Map.class)).andReturn(exchangeResponse);

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        assertFalse(exchangeAdapter.cancelOrder(ORDER_ID_TO_CANCEL, MARKET_ID));
        PowerMock.verifyAll();
    }

    @Test(expected = ExchangeNetworkException.class)
    public void testCancelOrderHandlesExchangeNetworkException() throws Exception {

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);
        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                eq(TradingApi.HttpMethod.POST),
                eq(CANCEL_ORDER),
                anyObject(BtcMarketsExchangeAdapter.BtcMarketsCancelOrderRequest.class),
                anyObject(Map.class)).
                andThrow(new ExchangeNetworkException("I’ve thought of an ending for my book – “And he lived happily " +
                        "ever after… to the end of his days."));

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        exchangeAdapter.cancelOrder(ORDER_ID_TO_CANCEL, MARKET_ID);
        PowerMock.verifyAll();
    }

    @Test(expected = TradingApiException.class)
    public void testCancelOrderHandlesUnexpectedException() throws Exception {

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);
        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                eq(TradingApi.HttpMethod.POST),
                eq(CANCEL_ORDER),
                anyObject(BtcMarketsExchangeAdapter.BtcMarketsCancelOrderRequest.class),
                anyObject(Map.class)).
                andThrow(new IllegalStateException("A Balrog. A demon of the ancient world. This foe is beyond any of" +
                        " you. Run!"));

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        exchangeAdapter.cancelOrder(ORDER_ID_TO_CANCEL, MARKET_ID);
        PowerMock.verifyAll();
    }

    // ------------------------------------------------------------------------------------------------
    //  Create Orders tests
    // ------------------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    public void testCreateOrderToBuyIsSuccessful() throws Exception {

        // Load the canned response from the exchange
        final byte[] encoded = Files.readAllBytes(Paths.get(CREATE_ORDER_SUCCESS_JSON_RESPONSE));
        final AbstractExchangeAdapter.ExchangeHttpResponse exchangeResponse =
                new AbstractExchangeAdapter.ExchangeHttpResponse(200, "OK", new String(encoded, StandardCharsets.UTF_8));

        // Mock out postData so we can assert the contents passed to the transport layer are what we expect.
        final BtcMarketsExchangeAdapter.BtcMarketsCreateOrderRequest createOrderRequest = new BtcMarketsExchangeAdapter.BtcMarketsCreateOrderRequest();
        createOrderRequest.volume = BUY_ORDER_QUANTITY;
        createOrderRequest.price = BUY_ORDER_PRICE;
        createOrderRequest.instrument = BtcMarketsExchangeAdapter.MarketConfig.configOf(MARKET_ID).getBaseCurrency();
        createOrderRequest.currency = BtcMarketsExchangeAdapter.MarketConfig.configOf(MARKET_ID).getCounterCurrency();
        createOrderRequest.ordertype = "Market";
        createOrderRequest.orderSide = "Bid";

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);

        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                eq(TradingApi.HttpMethod.POST),
                eq(CREATE_ORDER),
                eq(gson.toJson(createOrderRequest)),
                eq(null)).andReturn(exchangeResponse);

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        final String orderId = exchangeAdapter.createOrder(MARKET_ID, OrderType.BUY, BUY_ORDER_QUANTITY, BUY_ORDER_PRICE);
        assertTrue(orderId.equals("100"));

        PowerMock.verifyAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCreateOrderToSellIsSuccessful() throws Exception {

        // Load the canned response from the exchange
        final byte[] encoded = Files.readAllBytes(Paths.get(CREATE_ORDER_SUCCESS_JSON_RESPONSE));
        final AbstractExchangeAdapter.ExchangeHttpResponse exchangeResponse =
                new AbstractExchangeAdapter.ExchangeHttpResponse(200, "OK", new String(encoded, StandardCharsets.UTF_8));

        // Mock out postData so we can assert the contents passed to the transport layer are what we expect.
        final BtcMarketsExchangeAdapter.BtcMarketsCreateOrderRequest createOrderRequest = new BtcMarketsExchangeAdapter.BtcMarketsCreateOrderRequest();
        createOrderRequest.volume = SELL_ORDER_QUANTITY;
        createOrderRequest.price = SELL_ORDER_PRICE;
        createOrderRequest.instrument = BtcMarketsExchangeAdapter.MarketConfig.configOf(MARKET_ID).getBaseCurrency();
        createOrderRequest.currency = BtcMarketsExchangeAdapter.MarketConfig.configOf(MARKET_ID).getCounterCurrency();
        createOrderRequest.ordertype = "Market";
        createOrderRequest.orderSide = "Ask";

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);

        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                eq(TradingApi.HttpMethod.POST),
                eq(CREATE_ORDER),
                eq(gson.toJson(createOrderRequest)),
                eq(null)).andReturn(exchangeResponse);

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        final String orderId = exchangeAdapter.createOrder(MARKET_ID, OrderType.SELL, SELL_ORDER_QUANTITY, SELL_ORDER_PRICE);
        assertTrue(orderId.equals("100"));

        PowerMock.verifyAll();
    }

    @Test(expected = TradingApiException.class)
    public void testCreateOrderExchangeErrorResponse() throws Exception {

        // Load the canned response from the exchange
        final byte[] encoded = Files.readAllBytes(Paths.get(CREATE_ORDER_FAIL_JSON_RESPONSE));
        final AbstractExchangeAdapter.ExchangeHttpResponse exchangeResponse =
                new AbstractExchangeAdapter.ExchangeHttpResponse(200, "OK", new String(encoded, StandardCharsets.UTF_8));

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);
        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                eq(TradingApi.HttpMethod.POST),
                eq(CREATE_ORDER),
                anyObject(String.class),
                eq(null)).andReturn(exchangeResponse);

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        exchangeAdapter.createOrder(MARKET_ID, OrderType.SELL, SELL_ORDER_QUANTITY, SELL_ORDER_PRICE);
        PowerMock.verifyAll();
    }

    @Test(expected = ExchangeNetworkException.class)
    public void testCreateOrderHandlesExchangeNetworkException() throws Exception {

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);
        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                eq(TradingApi.HttpMethod.POST),
                eq(CREATE_ORDER),
                anyObject(String.class),
                eq(null)).andThrow(new ExchangeNetworkException("It’s like in the great stories, Mr. Frodo, the ones that really " +
                        "mattered. Full of darkness and danger, they were... Those were the stories that stayed" +
                        " with you, that meant something, even if you were too small to understand why. But I think, " +
                        "Mr. Frodo, I do understand... There’s some good in this world, Mr. Frodo, and it’s worth" +
                        " fighting for."));

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        exchangeAdapter.createOrder(MARKET_ID, OrderType.SELL, SELL_ORDER_QUANTITY, SELL_ORDER_PRICE);
        PowerMock.verifyAll();
    }

    @Test(expected = TradingApiException.class)
    public void testCreateOrderHandlesUnexpectedException() throws Exception {

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);
        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                eq(TradingApi.HttpMethod.POST),
                eq(CREATE_ORDER),
                anyObject(String.class),
                eq(null)).
                andThrow(new IllegalArgumentException("We needs it. Must have the precious. They stole it from us. " +
                        "Sneaky little hobbitses, wicked, tricksy, false. No, not master... Master’s my friend. " +
                        "You don’t have any friends. Nobody likes you. Not listening. I’m not listening. You’re a liar." +
                        " And a thief. Murderer. Go away... I hate you... Leave now and never come back.”"));

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        exchangeAdapter.createOrder(MARKET_ID, OrderType.BUY, BUY_ORDER_QUANTITY, BUY_ORDER_PRICE);
        PowerMock.verifyAll();
    }

    // ------------------------------------------------------------------------------------------------
    //  Get Market Orders tests
    // ------------------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    public void testGettingMarketOrders() throws Exception {

        // Load the canned response from the exchange
        final byte[] encoded = Files.readAllBytes(Paths.get(ORDERBOOK_JSON_RESPONSE));
        final AbstractExchangeAdapter.ExchangeHttpResponse exchangeResponse =
                new AbstractExchangeAdapter.ExchangeHttpResponse(200, "OK", new String(encoded, StandardCharsets.UTF_8));

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);

        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                eq(TradingApi.HttpMethod.GET),
                eq(ORDERBOOK),
                eq(null),
                eq(null)).andReturn(exchangeResponse);

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        final MarketOrderBook marketOrderBook = exchangeAdapter.getMarketOrders(MARKET_ID);

        // assert some key stuff; we're not testing GSON here.
        assertTrue(marketOrderBook.getMarketId().equals(MARKET_ID));

        final BigDecimal buyPrice = new BigDecimal("844.0");
        final BigDecimal buyQuantity = new BigDecimal("0.00489636");
        final BigDecimal buyTotal = buyPrice.multiply(buyQuantity);

        assertTrue(marketOrderBook.getBuyOrders().size() == 4);
        assertTrue(marketOrderBook.getBuyOrders().get(0).getType() == OrderType.BUY);
        assertTrue(marketOrderBook.getBuyOrders().get(0).getPrice().compareTo(buyPrice) == 0);
        assertTrue(marketOrderBook.getBuyOrders().get(0).getQuantity().compareTo(buyQuantity) == 0);
        assertTrue(marketOrderBook.getBuyOrders().get(0).getTotal().compareTo(buyTotal) == 0);

        final BigDecimal sellPrice = new BigDecimal("844.98");
        final BigDecimal sellQuantity = new BigDecimal("0.45077821");
        final BigDecimal sellTotal = sellPrice.multiply(sellQuantity);

        assertTrue(marketOrderBook.getSellOrders().size() == 4);
        assertTrue(marketOrderBook.getSellOrders().get(0).getType() == OrderType.SELL);
        assertTrue(marketOrderBook.getSellOrders().get(0).getPrice().compareTo(sellPrice) == 0);
        assertTrue(marketOrderBook.getSellOrders().get(0).getQuantity().compareTo(sellQuantity) == 0);
        assertTrue(marketOrderBook.getSellOrders().get(0).getTotal().compareTo(sellTotal) == 0);

        PowerMock.verifyAll();
    }

    @Test(expected = ExchangeNetworkException.class)
    public void testGettingMarketOrdersHandlesExchangeNetworkException() throws Exception {

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(BtcMarketsExchangeAdapter.class,
                MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);
        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                anyObject(TradingApi.HttpMethod.class),
                anyString(),
                anyString(),
                anyObject(Map.class)).
                andThrow(new ExchangeNetworkException("All we have to decide is what to do with the time that is given" +
                        " to us."));

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        exchangeAdapter.getMarketOrders(MARKET_ID);
        PowerMock.verifyAll();
    }

    @Test(expected = TradingApiException.class)
    public void testGettingMarketOrdersHandlesUnexpectedException() throws Exception {

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);
        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                anyObject(TradingApi.HttpMethod.class),
                anyString(),
                anyString(),
                anyObject(Map.class)).
                andThrow(new IllegalArgumentException("The board is set, the pieces are moving. We come to it at last, " +
                        "the great battle of our time."));

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        exchangeAdapter.getMarketOrders(MARKET_ID);
        PowerMock.verifyAll();
    }

    // ------------------------------------------------------------------------------------------------
    //  Get Latest Market Price tests
    // ------------------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    public void testGettingLatestMarketPriceSuccessfully() throws Exception {

        // Load the canned response from the exchange
        final byte[] encoded = Files.readAllBytes(Paths.get(TICK_JSON_RESPONSE));
        final AbstractExchangeAdapter.ExchangeHttpResponse exchangeResponse =
                new AbstractExchangeAdapter.ExchangeHttpResponse(200, "OK", new String(encoded, StandardCharsets.UTF_8));

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);

        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                eq(TradingApi.HttpMethod.GET),
                eq(TICK),
                eq(null),
                eq(null)).andReturn(exchangeResponse);

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        final BigDecimal latestMarketPrice = exchangeAdapter.getLatestMarketPrice(MARKET_ID).setScale(8, BigDecimal.ROUND_HALF_UP);
        assertTrue(latestMarketPrice.compareTo(new BigDecimal("845.0")) == 0);
        PowerMock.verifyAll();
    }

    @Test(expected = ExchangeNetworkException.class)
    public void testGettingLatestMarketPriceHandlesExchangeNetworkException() throws Exception {

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);
        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                eq(TradingApi.HttpMethod.GET),
                eq(TICK),
                eq(null),
                anyObject(Map.class)).
                andThrow(new ExchangeNetworkException("I would rather share one lifetime with you than face all the" +
                        " Ages of this world alone."));

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        exchangeAdapter.getLatestMarketPrice(MARKET_ID);
        PowerMock.verifyAll();
    }

    @Test(expected = TradingApiException.class)
    public void testGettingLatestMarketPriceHandlesUnexpectedException() throws Exception {

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);
        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                eq(TradingApi.HttpMethod.GET),
                eq(TICK),
                eq(null),
                anyObject(Map.class)).
                andThrow(new IllegalArgumentException("What has happened before will happen again. What has been done " +
                        "before will be done again. There is nothing new in the whole world. \"Look,\" they say, " +
                        "\"here is something new!\" But no, it has all happened before, long before we were born." +
                        " No one remembers what has happened in the past, and no one in days to come will remember what" +
                        " happens between now and then."));

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        exchangeAdapter.getLatestMarketPrice(MARKET_ID);
        PowerMock.verifyAll();
    }

    // ------------------------------------------------------------------------------------------------
    //  Get Tick tests
    // ------------------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    public void testGettingTickerSuccessfully() throws Exception {

        // Load the canned response from the exchange
        final byte[] encoded = Files.readAllBytes(Paths.get(TICK_JSON_RESPONSE));
        final AbstractExchangeAdapter.ExchangeHttpResponse exchangeResponse =
                new AbstractExchangeAdapter.ExchangeHttpResponse(200, "OK", new String(encoded, StandardCharsets.UTF_8));

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);

        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                eq(TradingApi.HttpMethod.GET),
                eq(TICK),
                eq(null),
                eq(null)).andReturn(exchangeResponse);

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        final Ticker ticker = exchangeAdapter.getTicker(MARKET_ID);
        assertTrue(ticker.getLast().compareTo(new BigDecimal("845.0")) == 0);
        assertTrue(ticker.getAsk().compareTo(new BigDecimal("844.98")) == 0);
        assertTrue(ticker.getBid().compareTo(new BigDecimal("844.0")) == 0);
        assertTrue(ticker.getHigh() == null); // high not supplied by BTCMarkets
        assertTrue(ticker.getLow() == null); // low not supplied by BTCMarkets
        assertTrue(ticker.getOpen() == null); // open not supplied by BTCMarkets
        assertTrue(ticker.getVolume().compareTo(new BigDecimal("172.60804")) == 0);
        assertTrue(ticker.getVwap() == null); // vwap not supplied by BTCMarkets
        assertTrue(ticker.getTimestamp() == 1476242958L);

        PowerMock.verifyAll();
    }

    @Test(expected = ExchangeNetworkException.class)
    public void testGettingTickerHandlesExchangeNetworkException() throws Exception {

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);
        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                eq(TradingApi.HttpMethod.GET),
                eq(TICK),
                eq(null),
                anyObject(Map.class)).
                andThrow(new ExchangeNetworkException("Where the hell can I get eyes like that?"));

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        exchangeAdapter.getTicker(MARKET_ID);
        PowerMock.verifyAll();
    }

    @Test(expected = TradingApiException.class)
    public void testGettingTickerHandlesUnexpectedException() throws Exception {

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);
        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                eq(TradingApi.HttpMethod.GET),
                eq(TICK),
                eq(null),
                anyObject(Map.class)).
                andThrow(new IllegalArgumentException("All you people are so scared of me. " +
                        "Most days I'd take that as a compliment. But it ain't me you gotta worry about now."));

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        exchangeAdapter.getTicker(MARKET_ID);
        PowerMock.verifyAll();
    }

}