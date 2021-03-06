package com.djavid.br_server.tasks;

import com.djavid.br_server.BrServerApplication;
import com.djavid.br_server.Config;
import com.djavid.br_server.model.entity.CurrencyUpdate;
import com.djavid.br_server.model.entity.RegistrationToken;
import com.djavid.br_server.model.entity.Subscribe;
import com.djavid.br_server.model.entity.Ticker;
import com.djavid.br_server.model.entity.cryptonator.CoinMarketCapTicker;
import com.djavid.br_server.model.repository.CurrencyUpdateRepository;
import com.djavid.br_server.model.repository.RegistrationTokenRepository;
import com.djavid.br_server.model.repository.SubscribeRepository;
import com.djavid.br_server.model.repository.TickerRepository;
import com.djavid.br_server.push.AndroidPushNotificationsService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static com.djavid.br_server.Config.*;


@Component
public class ScheduledTasks {

    private RegistrationTokenRepository registrationTokenRepository;
    private SubscribeRepository subscribeRepository;
    private TickerRepository tickerRepository;
    private CurrencyUpdateRepository currencyUpdateRepository;

    private AndroidPushNotificationsService androidPushNotificationsService;
    private RestTemplate restTemplate;


    public ScheduledTasks(RegistrationTokenRepository registrationTokenRepository,
                          SubscribeRepository subscribeRepository,
                          TickerRepository tickerRepository,
                          CurrencyUpdateRepository currencyUpdateRepository,
                          AndroidPushNotificationsService androidPushNotificationsService) {
        this.registrationTokenRepository = registrationTokenRepository;
        this.androidPushNotificationsService = androidPushNotificationsService;
        this.subscribeRepository = subscribeRepository;
        this.tickerRepository = tickerRepository;
        this.currencyUpdateRepository = currencyUpdateRepository;
        this.restTemplate = new RestTemplate();
    }


    @Scheduled(fixedDelay = Config.FIXED_DELAY)
    public void getCurrentRate() {

        for (String country : country_coins) {
            List<CoinMarketCapTicker> pairs = getCurrentPairs(country);
            saveUpdatesToRepository(pairs);
            logUpdate(pairs, country);
            notifyAllSubscribes(pairs);
        }

    }

    private void saveUpdatesToRepository(List<CoinMarketCapTicker> pairs) {

        for (CoinMarketCapTicker ticker : pairs) {
            CurrencyUpdate currencyUpdate = currencyUpdateRepository
                    .findCurrencyUpdateByCryptoIdAndCountryId(ticker.getSymbol(), ticker.getCountry_symbol());

            if (currencyUpdate == null) {
                CurrencyUpdate newCurrUpdate = new CurrencyUpdate();

                newCurrUpdate.setData(ticker);
                currencyUpdateRepository.save(newCurrUpdate);
            } else {
                currencyUpdate.setData(ticker);
                currencyUpdateRepository.save(currencyUpdate);
            }
        }
    }

    private List<CoinMarketCapTicker> getCurrentPairs(String country) {

        List<CoinMarketCapTicker> pairs = new ArrayList<>();

//        for (String country : country_coins) {

            System.out.println(country);

            CoinMarketCapTicker[] coinMarketList = restTemplate
                    .getForObject(Config.COINMARKETCAP_URL + country + "&limit=400", CoinMarketCapTicker[].class);

//            CoinMarketCapTicker[] coinMarketList = restTemplate
//                    .getForObject(Config.COINMARKETCAP_URL + country, CoinMarketCapTicker[].class);
//            CoinMarketCapTicker[] coinMarketList2 = restTemplate
//                    .getForObject(Config.COINMARKETCAP_URL + country + "&start=100", CoinMarketCapTicker[].class);
//            CoinMarketCapTicker[] coinMarketList3 = restTemplate
//                    .getForObject(Config.COINMARKETCAP_URL + country + "&start=200", CoinMarketCapTicker[].class);
//            CoinMarketCapTicker[] coinMarketList4 = restTemplate
//                    .getForObject(Config.COINMARKETCAP_URL + country + "&start=300", CoinMarketCapTicker[].class);

            List<CoinMarketCapTicker> list = new ArrayList<>(Arrays.asList(coinMarketList));
//            list.addAll(Arrays.asList(coinMarketList2));
//            list.addAll(Arrays.asList(coinMarketList3));
//            list.addAll(Arrays.asList(coinMarketList4));

            for (String coin_symbol : crypto_coins_array) {
                list.stream()
                        .filter(ticker -> ticker.getSymbol().equals(coin_symbol))
                        .findFirst()
                        .ifPresent(ticker -> {
                            ticker.setCountry_symbol(country);
                            pairs.add(ticker);
                        });
            }
//        }

        return pairs;
    }

    private void notifyAllSubscribes(List<CoinMarketCapTicker> pairs) {

        Iterable<Subscribe> subscribes = subscribeRepository.findAll();

        subscribes.forEach(subscribe -> {
            Ticker ticker = tickerRepository.findOne(subscribe.getTickerId());
            pairs.stream()
                    .filter(pair -> pair.getCountry_symbol().equals(ticker.getCountryId())
                            && pair.getSymbol().equals(ticker.getCryptoId()))
                    .findFirst()
                    .ifPresent(pair -> {

                        if (checkForSending(subscribe, pair)) {
                            sendPush(subscribe, pair);

                            if (subscribe.getChange_percent() > 0) {
                                subscribe.setValue(pair.getPrice().toString());
                                subscribeRepository.save(subscribe);
                            } else {
                                subscribeRepository.delete(subscribe);
                            }
                        }

                    });
        });

    }

    private void logUpdate(List<CoinMarketCapTicker> pairs, String country) {
//        String summary = "";
//        for (CoinMarketCapTicker ticker : pairs)
//            summary += "{" + ticker.getCountry_symbol() + "-" + ticker.getSymbol() + " = " + ticker.getPrice() + "} ";
//        BrServerApplication.log.info(summary);

        TimeZone.setDefault(TimeZone.getTimeZone("UTC+03:00"));
        BrServerApplication.log.info("Cryptorates updated for " + country + " at " + new Date(System.currentTimeMillis()));

    }

    private boolean checkForSending(Subscribe subscribe, CoinMarketCapTicker ticker) {

        Double sub_price = Double.parseDouble(subscribe.getValue());

        if (subscribe.getChange_percent() > 0) {

            double needed_change = ticker.getPrice() * subscribe.getChange_percent();
            double actual_change = Math.abs(ticker.getPrice() - sub_price);

            if (actual_change > needed_change) return true;

        } else {

            if (subscribe.isTrendingUp()) {
                if (ticker.getPrice() >= sub_price) return true;
            } else {
                if (ticker.getPrice() <= sub_price) return true;
            }

        }

        return false;
    }

    private void sendPush(Subscribe subscribe, CoinMarketCapTicker capTicker) {

        Ticker ticker = tickerRepository.findOne(subscribe.getTickerId());
        RegistrationToken token = registrationTokenRepository.findOne(ticker.getTokenId());

        String title = getPushTitle(subscribe, capTicker);
        String desc = getPushDescription(subscribe, capTicker, ticker);

        BrServerApplication.log.info("Sending push notification with title ='" + title + "' and body = '" + desc + "';");

        try {
            CompletableFuture<String> pushNotification = androidPushNotificationsService.send(token.getToken(), title, desc);
            CompletableFuture.allOf(pushNotification).join();
            String firebaseResponse = pushNotification.get();

            BrServerApplication.log.info(firebaseResponse);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getPushTitle(Subscribe subscribe, CoinMarketCapTicker capTicker) {

        String curr_full = capitalize(capTicker.getId());

        if (subscribe.getChange_percent() > 0) {

            if (capTicker.getPrice() > Double.parseDouble(subscribe.getValue()))
                return curr_full + " вырос";
            else
                return curr_full + " упал";

        } else {

            if (subscribe.isTrendingUp()) {
                return curr_full + " вырос";
            } else {
                return curr_full + " упал";
            }

        }
    }

    private String getPushDescription(Subscribe subscribe, CoinMarketCapTicker capTicker, Ticker ticker) {

        String curr_full = capitalize(capTicker.getId());
        String desc;

        if (subscribe.getChange_percent() > 0) {

            if (capTicker.getPrice() > Double.parseDouble(subscribe.getValue()))
                desc = "Цена " + curr_full + " выросла более чем на " + subscribe.getChange_percent() * 100
                        + "% до " + convertPrice(capTicker.getPrice()) + " "
                        + ticker.getCountryId() + "!";
            else
                desc = "Цена " + curr_full + " упала более чем на " + subscribe.getChange_percent() * 100
                        + "% до " + convertPrice(capTicker.getPrice()) + " "
                        + ticker.getCountryId() + "!";

        } else {
            if (subscribe.isTrendingUp()) {
                desc = "Цена " + curr_full + " выросла до " + convertPrice(capTicker.getPrice()) + " "
                        + ticker.getCountryId() + "!";
            } else {
                desc = "Цена " + curr_full + " упала до " + convertPrice(capTicker.getPrice()) + " "
                        + ticker.getCountryId() + "!";
            }
        }

        return desc;
    }

    private String capitalize(final String line) {
        return Character.toUpperCase(line.charAt(0)) + line.substring(1);
    }

}
