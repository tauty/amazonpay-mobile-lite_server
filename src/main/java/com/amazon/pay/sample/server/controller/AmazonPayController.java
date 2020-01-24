package com.amazon.pay.sample.server.controller;

import com.amazon.pay.Client;
import com.amazon.pay.Config;
import com.amazon.pay.exceptions.AmazonServiceException;
import com.amazon.pay.impl.PayClient;
import com.amazon.pay.impl.PayConfig;
import com.amazon.pay.request.AuthorizeRequest;
import com.amazon.pay.request.ConfirmOrderReferenceRequest;
import com.amazon.pay.request.GetOrderReferenceDetailsRequest;
import com.amazon.pay.request.SetOrderReferenceDetailsRequest;
import com.amazon.pay.response.parser.AuthorizeResponseData;
import com.amazon.pay.response.parser.ConfirmOrderReferenceResponseData;
import com.amazon.pay.response.parser.GetOrderReferenceDetailsResponseData;
import com.amazon.pay.response.parser.SetOrderReferenceDetailsResponseData;
import com.amazon.pay.sample.server.storage.DatabaseMock;
import com.amazon.pay.sample.server.storage.DatabaseMock.Item;
import com.amazon.pay.sample.server.storage.DatabaseMock.Order;
import com.amazon.pay.sample.server.utils.TokenUtil;
import com.amazon.pay.types.CurrencyCode;
import com.amazon.pay.types.Region;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Controller
public class AmazonPayController {

    /**
     * merchant.propertiesからの読み込み
     */
    @Value("${client.id}")
    private String clientId;

    /**
     * merchant.propertiesからの読み込み
     */
    @Value("${seller.id}")
    private String sellerId;

    /**
     * merchant.propertiesからの読み込み
     */
    @Value("${access.key}")
    private String accessKey;

    /**
     * merchant.propertiesからの読み込み
     */
    @Value("${secret.key}")
    private String secretKey;

    /**
     * 受注入力情報画面を表示.
     *
     * @param env アクセス元の環境. android/ios/ios-ui/browser のどれか
     * @return 画面生成templateの名前. "order"の時、「./src/main/resources/templates/order.html」
     */
    @GetMapping("/{env}/order")
    public String order(@PathVariable String env, Model model) {
        System.out.println("[order] " + env);

        model.addAttribute("env", env);

        return "order";
    }

    /**
     * 受注入力情報画面から呼び出されて、受注Objectを生成・保存する.
     *
     * @param env  アクセス元の環境. android/ios/ios-ui/browser のどれか
     * @param hd8  Kindle File HD8の購入数
     * @param hd10 Kindle File HD10の購入数
     * @return 受注Objectへのアクセス用token
     */
    @ResponseBody
    @PostMapping("/{env}/create_order_rest")
    public Map<String, String> createOrderREST(@PathVariable String env, @RequestParam int hd8, @RequestParam int hd10) {
        System.out.println("[createOrderREST] " + env + ", " + hd8 + ", " + hd10);
        return doCreateOrder(env, hd8, hd10);
    }

    private Map<String, String> doCreateOrder(String env, int hd8, int hd10) {

        // 受注Objectの生成/更新
        Order order = new Order();
        order.env = env;
        order.items = new ArrayList<>();
        if (hd8 > 0) {
            order.items.add(new Item("item0008", "Fire HD8", hd8, 8980));
        }
        if (hd10 > 0) {
            order.items.add(new Item("item0010", "Fire HD10", hd10, 15980));
        }
        order.price = order.items.stream().mapToLong(item -> item.summary).sum();
        order.priceTaxIncluded = (long) (1.08 * order.price);
        order.myOrderStatus = "CREATED";
        order.appKey = TokenUtil.createToken();

        // 受注Objectの保存
        DatabaseMock.storeOrder(order);

        // 受注Objectのcacheへの保存と、アクセス用tokenの返却
        // Note: tokenを用いる理由については、TokenUtilのJavadoc参照.
        String token = TokenUtil.storeByToken(order);
        Map<String, String> map = new HashMap<>();
        map.put("token", token);
        map.put("appKey", order.appKey);
        return map;
    }

    /**
     * Chrome Custom Tabsの起動時に呼び出されて、ボタンWidget表示画面を表示する.
     * Note: ボタンWidget表示画面は見た目上ではLoading画像のみが表示されており、次の購入確定画面に自動的に遷移する.
     * 裏では非表示のボタンWidgetを読み込まれており、読み込みが完了すると自動的にJavaScriptでボタンWidgetがクリックされる.
     *
     * @param token    受注Objectへのアクセス用token
     * @param response responseオブジェクト
     * @param model    画面生成templateに渡す値を設定するObject
     * @return 画面生成templateの名前. "order"の時、「./src/main/resources/templates/order.html」
     */
    @GetMapping("/button")
    public String button(@RequestParam String token, HttpServletResponse response, Model model) {
        System.out.println("[button] token: " + token);

        // redirect処理でconfirm_orderに戻ってきたときにtokenが使用できるよう、Cookieに登録
        // Note: Session Fixation 対策に、tokenをこのタイミングで更新する.
        addCookie(response, "token", TokenUtil.move(token));
        // 更新前のtokenも、APPに戻ったタイミングでの確認用に保持する
        addCookie(response, "appToken", token);

        model.addAttribute("clientId", clientId);
        model.addAttribute("sellerId", sellerId);

        return "button";
    }

    /**
     * ボタンWidget表示画面から呼び出されて、アドレスWidget・支払いWidgetのある購入確定画面を表示する.
     *
     * @param token    受注Objectへのアクセス用token
     * @param response responseオブジェクト
     * @param model    画面生成templateに渡す値を設定するObject
     * @return 画面生成templateの名前. "order"の時、「./src/main/resources/templates/order.html」
     */
    @GetMapping("/confirm_order")
    public String confirmOrder(@CookieValue(required = false) String token, @CookieValue(required = false) String appToken,
                               HttpServletResponse response, Model model) {
        if (token == null) return "dummy"; // Chrome Custom Tabsが本URLを勝手にreloadすることがあるので、その対策.
        System.out.println("[confirm_order] token = " + token + ", appToken = " + appToken);

        // token & appToken のCookieからの削除
        removeCookie(response, "token");
        removeCookie(response, "appToken");

        Order order = TokenUtil.get(token);
        model.addAttribute("order", order);
        model.addAttribute("env", order.env);
        model.addAttribute("token", token);
        model.addAttribute("appToken", appToken);
        model.addAttribute("clientId", clientId);
        model.addAttribute("sellerId", sellerId);

        return "confirm_order";
    }

    /**
     * 購入確定画面でアドレスWidgetで住所を選択した時にAjaxで非同期に呼び出されて、Amazon Pay APIから取得した住所情報より送料を計算する.
     *
     * @param token            受注Objectへのアクセス用token
     * @param accessToken      Amazon Pay側の情報にアクセスするためのToken. ボタンWidgetクリック時に取得する.
     * @param orderReferenceId Amazon Pay側の受注管理番号.
     * @return 計算した送料・総合計金額を含んだJSON
     * @throws AmazonServiceException Amazon PayのAPIがthrowするエラー. 今回はサンプルなので特に何もしていないが、実際のコードでは正しく対処する.
     */
    @ResponseBody
    @PostMapping("/calc_postage")
    public Map<String, String> calcPostage(@RequestParam String token, @RequestParam String orderReferenceId
            , @RequestParam String accessToken) throws AmazonServiceException {
        System.out.println("[calc_postage]: " + token + ", " + orderReferenceId + ", " + accessToken);

        Config config = new PayConfig()
                .withSellerId(sellerId)
                .withAccessKey(accessKey)
                .withSecretKey(secretKey)
                .withCurrencyCode(CurrencyCode.JPY)
                .withSandboxMode(true)
                .withRegion(Region.JP);

        Client client = new PayClient(config);

        //--------------------------------------------
        // Amazon Pay側のOrderReferenceの詳細情報の取得
        //--------------------------------------------
        GetOrderReferenceDetailsRequest request = new GetOrderReferenceDetailsRequest(orderReferenceId);
        request.setAccessToken(accessToken);
        GetOrderReferenceDetailsResponseData response = client.getOrderReferenceDetails(request);

        Order order = TokenUtil.get(token);
        order.postage = calcPostage(response);
        order.totalPrice = order.priceTaxIncluded + order.postage;
        DatabaseMock.storeOrder(order);

        Map<String, String> map = new HashMap<>();
        map.put("postage", comma(order.postage));
        map.put("totalPrice", comma(order.totalPrice));
        return map;
    }

    private long calcPostage(GetOrderReferenceDetailsResponseData response) {
        String stateOrRegion = response.getDetails().getDestination().getPhysicalDestination().getStateOrRegion();
        if (stateOrRegion.equals("沖縄県") || stateOrRegion.equals("北海道")) {
            return 1080;
        } else {
            return 540;
        }
    }

    /**
     * 購入確定画面から呼び出されて、購入処理を実行してThanks画面へ遷移させる.
     *
     * @param token            受注Objectへのアクセス用token
     * @param accessToken      Amazon Pay側の情報にアクセスするためのToken. ボタンWidgetクリック時に取得する.
     * @param orderReferenceId Amazon Pay側の受注管理番号.
     * @param furiganaSei      ユーザが画面で入力した姓のフリガナ
     * @param furiganaMei      ユーザが画面で入力した名のフリガナ
     * @param pwd              ユーザが画面で入力したパスワード
     * @param model            画面生成templateに渡す値を設定するObject
     * @return 画面生成templateの名前. "order"の時、「./src/main/resources/templates/order.html」
     * @throws AmazonServiceException Amazon PayのAPIがthrowするエラー. 今回はサンプルなので特に何もしていないが、実際のコードでは正しく対処する.
     */
    @PostMapping("/purchase")
    public String purchase(@RequestParam String token, @RequestParam String accessToken,
                           @RequestParam String orderReferenceId, @RequestParam String furiganaSei,
                           @RequestParam String furiganaMei, @RequestParam String pwd,
                           Model model) throws AmazonServiceException {
        if ("OK".equals(purchaseRest(token, accessToken, orderReferenceId, furiganaSei, furiganaMei, pwd))) {
            Order order = TokenUtil.get(token);

            model.addAttribute("env", order.env);
            model.addAttribute("order", order);

            return "thanks";
        } else {
            // validation errorのとき、元の画面に戻ってエラーを表示する
            return "redirect:/button?token=" + token;
        }
    }

    /**
     * 購入確定画面から呼び出されて、購入処理を実行する.
     *
     * @param token            受注Objectへのアクセス用token
     * @param accessToken      Amazon Pay側の情報にアクセスするためのToken. ボタンWidgetクリック時に取得する.
     * @param orderReferenceId Amazon Pay側の受注管理番号.
     * @param furiganaSei      ユーザが画面で入力した姓のフリガナ
     * @param furiganaMei      ユーザが画面で入力した名のフリガナ
     * @param pwd              ユーザが画面で入力したパスワード
     * @return 処理が成功したとき: 'OK', 失敗したとき: 'NG'
     * @throws AmazonServiceException Amazon PayのAPIがthrowするエラー. 今回はサンプルなので特に何もしていないが、実際のコードでは正しく対処する.
     */
    @ResponseBody
    @PostMapping("/purchase_rest")
    public String purchaseRest(@RequestParam String token, @RequestParam String accessToken,
                               @RequestParam String orderReferenceId, @RequestParam String furiganaSei,
                               @RequestParam String furiganaMei, @RequestParam String pwd) throws AmazonServiceException {
        System.out.println("[purchase] " + token + ", " + orderReferenceId + ", " + accessToken +
                ", " + furiganaSei + ", " + furiganaMei + ", " + pwd);

        Order order = TokenUtil.get(token);
        order.orderReferenceId = orderReferenceId;

        //---------------------
        // Validation Check
        //---------------------
        boolean isAllValid = true;
        order.buyerFuriganaSei_msg = order.buyerFuriganaMei_msg = order.buyerPwd_msg = "";
        order.buyerFuriganaSei = furiganaSei;
        if (!furiganaSei.matches("([ァ-ン]|ー)+")) {
            isAllValid = false;
            order.buyerFuriganaSei_msg = "全角カタカナです!";
        }
        order.buyerFuriganaMei = furiganaMei;
        if (!furiganaMei.matches("([ァ-ン]|ー)+")) {
            isAllValid = false;
            order.buyerFuriganaMei_msg = "全角カタカナです!";
        }
        order.buyerPwd = pwd;
        if (pwd.equals("password")) {
            isAllValid = false;
            order.buyerPwd_msg = "簡単すぎます!";
        }
        if (!isAllValid) {
            DatabaseMock.storeOrder(order);
            return "NG";
        }

        //--------------------------------------------
        // Amazon Pay側のOrderReferenceの詳細情報の取得
        //--------------------------------------------
        Config config = new PayConfig()
                .withSellerId(sellerId)
                .withAccessKey(accessKey)
                .withSecretKey(secretKey)
                .withCurrencyCode(CurrencyCode.JPY)
                .withSandboxMode(true)
                .withRegion(Region.JP);

        Client client = new PayClient(config);

        GetOrderReferenceDetailsRequest request = new GetOrderReferenceDetailsRequest(orderReferenceId);
        // request.setAddressConsentToken(accessToken); // Note: It's old! should be removed!
        request.setAccessToken(accessToken);
        GetOrderReferenceDetailsResponseData response = client.getOrderReferenceDetails(request);

        System.out.println("<GetOrderReferenceDetailsResponseData>");
        System.out.println(response);
        System.out.println("</GetOrderReferenceDetailsResponseData>");

        // Amazon Pay側の受注詳細情報を、受注Objectに反映
        order.buyerName = emptyIfNull(response.getDetails().getBuyer().getName());
        order.buyerEmail = emptyIfNull(response.getDetails().getBuyer().getEmail());
        order.buyerPhone = emptyIfNull(response.getDetails().getBuyer().getPhone());
        order.destinationName = emptyIfNull(response.getDetails().getDestination().getPhysicalDestination().getName());
        order.destinationPhone = emptyIfNull(response.getDetails().getDestination().getPhysicalDestination().getPhone());
        order.destinationPostalCode = emptyIfNull(response.getDetails().getDestination().getPhysicalDestination().getPostalCode());
        order.destinationStateOrRegion = emptyIfNull(response.getDetails().getDestination().getPhysicalDestination().getStateOrRegion());
        order.destinationCity = emptyIfNull(response.getDetails().getDestination().getPhysicalDestination().getCity());
        order.destinationAddress1 = emptyIfNull(response.getDetails().getDestination().getPhysicalDestination().getAddressLine1());
        order.destinationAddress2 = emptyIfNull(response.getDetails().getDestination().getPhysicalDestination().getAddressLine2());
        order.destinationAddress3 = emptyIfNull(response.getDetails().getDestination().getPhysicalDestination().getAddressLine3());

        //--------------------------------
        // OrderReferenceの詳細情報の設定
        //--------------------------------
        SetOrderReferenceDetailsRequest setOrderReferenceDetailsRequest = new SetOrderReferenceDetailsRequest(orderReferenceId, String.valueOf(order.priceTaxIncluded));

        //set optional parameters
        setOrderReferenceDetailsRequest.setOrderCurrencyCode(CurrencyCode.JPY);
        setOrderReferenceDetailsRequest.setSellerNote(String.valueOf(order.items));
        setOrderReferenceDetailsRequest.setSellerOrderId(order.myOrderId);
        setOrderReferenceDetailsRequest.setStoreName("My Sweet Shop");

        //call API
        SetOrderReferenceDetailsResponseData responseSet = client.setOrderReferenceDetails(setOrderReferenceDetailsRequest);

        System.out.println("<SetOrderReferenceDetailsResponseData>");
        System.out.println(responseSet);
        System.out.println("</SetOrderReferenceDetailsResponseData>");

        //--------------------------------
        // OrderReferenceの確認
        //--------------------------------
        ConfirmOrderReferenceResponseData responseCon = client.confirmOrderReference(new ConfirmOrderReferenceRequest(orderReferenceId));
        // Note: it was not String, but request object!

        System.out.println("<ConfirmOrderReferenceResponseData>");
        System.out.println(responseCon);
        System.out.println("</ConfirmOrderReferenceResponseData>");

        //----------------------------------
        // Authorize(オーソリ, 与信枠確保)処理
        //----------------------------------
        AuthorizeRequest authorizeRequest = new AuthorizeRequest(orderReferenceId, generateId(), String.valueOf(order.totalPrice));

        //Set Optional parameters
        authorizeRequest.setAuthorizationCurrencyCode(CurrencyCode.JPY); //Overrides currency code set in Client
        authorizeRequest.setSellerAuthorizationNote("You can write something here.");
        authorizeRequest.setTransactionTimeout("0"); //Set to 0 for synchronous mode
//        authorizeRequest.setCaptureNow(true); // Set this to true if you want to capture the amount in the same API call

        //Call Authorize API
        AuthorizeResponseData authResponse = client.authorize(authorizeRequest);

        System.out.println("<AuthorizeResponseData>");
        System.out.println(authResponse);
        System.out.println("</AuthorizeResponseData>");

        // 受注Objectのステータスをオーソリ完了に設定して保存
        order.myOrderStatus = "AUTHORIZED";
        DatabaseMock.storeOrder(order);

        return "OK";
    }

    /**
     * Thanks画面で、受注Objectの詳細情報を表示する.
     *
     * @param token 受注Objectへのアクセス用token
     * @param model 画面生成templateに渡す値を設定するObject
     * @return 画面生成templateの名前. "order"の時、「./src/main/resources/templates/order.html」
     */
    @PostMapping("/thanks")
    public String thanks(@RequestParam String token, Model model) {
        System.out.println("[thanks] " + token);

        Order order = TokenUtil.get(token);
        model.addAttribute("order", order);
        model.addAttribute("env", order.env);

        return "thanks";
    }

    /**
     * エラー画面.
     *
     * @return 画面生成templateの名前. "order"の時、「./src/main/resources/templates/order.html」
     */
    @PostMapping("/error")
    @GetMapping("/error")
    public String error() {
        System.out.println("[error] ");

        return "error";
    }

    private void addCookie(HttpServletResponse response, String key, String value) {
        Cookie cookie = new Cookie(key, value);
        cookie.setSecure(true);
        response.addCookie(cookie);
    }

    private void removeCookie(HttpServletResponse response, String key) {
        Cookie cookie = new Cookie(key, "");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private String generateId() {
        return String.valueOf(Math.abs(ThreadLocalRandom.current().nextLong()));
    }

    private String emptyIfNull(String s) {
        return s == null ? "" : s;
    }

    private String comma(long num) {
        return comma(String.valueOf(num));
    }

    private String comma(String num) {
        int index = num.length() - 3;
        if (index <= 0) return num;
        return comma(num.substring(0, index)) + "," + num.substring(index);
    }
}
