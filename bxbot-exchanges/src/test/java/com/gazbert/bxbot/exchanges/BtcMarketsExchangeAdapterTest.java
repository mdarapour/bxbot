package com.gazbert.bxbot.exchanges;

import com.gazbert.bxbot.exchange.api.AuthenticationConfig;
import com.gazbert.bxbot.exchange.api.ExchangeConfig;
import com.gazbert.bxbot.exchange.api.NetworkConfig;
import com.gazbert.bxbot.exchange.api.OptionalConfig;
import com.gazbert.bxbot.trading.api.*;
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

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.Assert.*;

/**
 * Tests the behaviour of the BtcMarkets Exchange Adapter.
 *
 * @author mdarapour
 */
@RunWith(PowerMockRunner.class)
@PowerMockIgnore({"javax.crypto.*", "javax.management.*"})
@PrepareForTest(BtcMarketsExchangeAdapter.class)
public class BtcMarketsExchangeAdapterTest {

    // Canned JSON responses from exchange - expected to reside on filesystem relative to project root
    private static final String ORDERBOOK_JSON_RESPONSE = "./src/test/exchange-data/btcmarkets/orderbook.json";
    private static final String USERINFO_JSON_RESPONSE = "./src/test/exchange-data/okcoin/userinfo.json";
    private static final String USERINFO_ERROR_JSON_RESPONSE = "./src/test/exchange-data/okcoin/userinfo-error.json";
    private static final String TICKER_JSON_RESPONSE = "./src/test/exchange-data/okcoin/ticker.json";
    private static final String ORDER_INFO_JSON_RESPONSE = "./src/test/exchange-data/okcoin/order_info.json";
    private static final String ORDER_INFO_ERROR_JSON_RESPONSE = "./src/test/exchange-data/okcoin/order_info-error.json";
    private static final String TRADE_BUY_JSON_RESPONSE = "./src/test/exchange-data/okcoin/trade_buy.json";
    private static final String TRADE_SELL_JSON_RESPONSE = "./src/test/exchange-data/okcoin/trade_sell.json";
    private static final String TRADE_ERROR_JSON_RESPONSE = "./src/test/exchange-data/okcoin/trade-error.json";
    private static final String CANCEL_ORDER_JSON_RESPONSE = "./src/test/exchange-data/okcoin/cancel_order.json";
    private static final String CANCEL_ORDER_ERROR_JSON_RESPONSE = "./src/test/exchange-data/okcoin/cancel_order-error.json";

    // Canned test data
    private static final String MARKET_ID = "BTC/AUD";
    private static final BigDecimal BUY_ORDER_PRICE = new BigDecimal("200.18");
    private static final BigDecimal BUY_ORDER_QUANTITY = new BigDecimal("0.01");
    private static final BigDecimal SELL_ORDER_PRICE = new BigDecimal("300.176");
    private static final BigDecimal SELL_ORDER_QUANTITY = new BigDecimal("0.01");
    private static final String ORDER_ID_TO_CANCEL = "99671870";

    // Exchange API calls
    private static final String ORDERBOOK = "/market/"+ MARKET_ID +"/orderbook";
    private static final String ORDER_INFO = "order_info.do";
    private static final String USERINFO = "userinfo.do";
    private static final String TICKER = "ticker.do";
    private static final String TRADE = "trade.do";
    private static final String CANCEL_ORDER = "cancel_order.do";

    // Mocked out methods
    private static final String MOCKED_CREATE_REQUEST_PARAM_MAP_METHOD = "createRequestParamMap";
    private static final String MOCKED_SEND_AUTHENTICATED_REQUEST_TO_EXCHANGE_METHOD = "sendAuthenticatedRequestToExchange";
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

        exchangeConfig = PowerMock.createMock(ExchangeConfig.class);
        expect(exchangeConfig.getAuthenticationConfig()).andReturn(authenticationConfig);
        expect(exchangeConfig.getNetworkConfig()).andReturn(networkConfig);
        expect(exchangeConfig.getOptionalConfig()).andReturn(optionalConfig);
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

        // Mock out param map so we can assert the contents passed to the transport layer are what we expect.
        final Map<String, String> requestParamMap = PowerMock.createMock(Map.class);
        expect(requestParamMap.put("symbol", MARKET_ID)).andStubReturn(null);

        // Partial mock so we do not send stuff down the wire
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
                MOCKED_CREATE_REQUEST_PARAM_MAP_METHOD);

        PowerMock.expectPrivate(exchangeAdapter, MOCKED_CREATE_REQUEST_PARAM_MAP_METHOD).andReturn(requestParamMap);
        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD, eq(TradingApi.HttpMethod.GET),
                eq(ORDERBOOK), eq(null), eq(requestParamMap)).andReturn(exchangeResponse);

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
        final BtcMarketsExchangeAdapter exchangeAdapter = PowerMock.createPartialMockAndInvokeDefaultConstructor(
                BtcMarketsExchangeAdapter.class, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD);
        PowerMock.expectPrivate(exchangeAdapter, MOCKED_SEND_PUBLIC_REQUEST_TO_EXCHANGE_METHOD,
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
                anyObject(Map.class)).
                andThrow(new IllegalArgumentException("The board is set, the pieces are moving. We come to it at last, " +
                        "the great battle of our time."));

        PowerMock.replayAll();
        exchangeAdapter.init(exchangeConfig);

        exchangeAdapter.getMarketOrders(MARKET_ID);
        PowerMock.verifyAll();
    }
}