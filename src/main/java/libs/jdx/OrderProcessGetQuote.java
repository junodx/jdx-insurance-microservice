package libs.jdx;

import com.junodx.api.connectors.messaging.payloads.EventType;
import com.junodx.api.models.commerce.Order;

public class OrderProcessGetQuote implements OrderProcess {
    @Override
    public OrderProcessResponse invoke(Order order) {

        System.out.println("About to invoke get quote");
        return null;
    }
}