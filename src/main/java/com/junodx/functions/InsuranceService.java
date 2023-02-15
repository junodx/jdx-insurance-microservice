package com.junodx.functions;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.junodx.api.connectors.messaging.payloads.EntityPayload;
import com.junodx.api.connectors.messaging.payloads.EventType;
import com.junodx.api.models.commerce.Order;
import com.junodx.api.models.commerce.OrderStatus;
import com.junodx.api.models.commerce.types.OrderStatusType;
import com.junodx.api.services.exceptions.JdxServiceException;
import libs.jdx.*;


import java.util.*;

public class InsuranceService implements RequestHandler<Map<String,String>, String> {

    ObjectMapper mapper = new ObjectMapper();

    Map<OrderStatusType, OrderProcess> functions = new HashMap<>();

    final String queueUrl = "https://sqs.us-east-2.amazonaws.com/352009045849/jdx-insurance-update-queue.fifo";

    public InsuranceService() {
        functions.put(OrderStatusType.CREATED, new OrderProcessGetQuote());
        functions.put(OrderStatusType.SAMPLE_COLLECTED, new OrderProcessCreateAccession());
        functions.put(OrderStatusType.RECEIVED, new OrderProcessCreateAccession());
        functions.put(OrderStatusType.LABORATORY_PROCESSING, new OrderProcessCreateAccession());
        functions.put(OrderStatusType.RESULTS_IN_REVIEW, new OrderProcessCreateAccession());
        functions.put(OrderStatusType.RESULTS_AVAILABLE, new OrderProcessUpdateAccession());
    }

    @Override
    public String handleRequest(Map<String, String> stringStringMap, Context context) {

        //final AmazonSQS sqs = AmazonSQSClientBuilder.defaultClient();

        BasicAWSCredentials credentials = new BasicAWSCredentials("AKIAVD5K7Y5MSAT5SVMF", "71HHX/dfAHqL1dtLEQTHHduZrgAntyxkA0hfK+cR");

        AmazonSQS sqs = AmazonSQSClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .withRegion(Regions.US_EAST_1)
                .build();

        ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(queueUrl)
                .withWaitTimeSeconds(10)
                .withMaxNumberOfMessages(10);

        List<Message> sqsMessages = sqs.receiveMessage(receiveMessageRequest).getMessages();

   System.out.println("Rcvd " + sqsMessages.size() + " messages");

        Map<String, List<OrderEvent>> list = processOrders(sqsMessages);
        deleteProcessedMessages(list, sqs, sqsMessages);

        try {
            for(Message m : sqsMessages)
                System.out.println(mapper.writeValueAsString(m.getBody()));
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }


        return "200 OK: ";
    }

    public Map<String, List<OrderEvent>> processOrders(List<Message> messages) throws JdxServiceException {
        try {
            Map<String, List<OrderEvent>> orderLists = buildOrderLists(messages);
            Iterator eventKeys = null;

            //Iterate through each unique orderId
            if(orderLists != null)
                if(orderLists.keySet() != null)
                    eventKeys = orderLists.keySet().iterator();

            while (eventKeys != null && eventKeys.hasNext()) {
                String orderId = (String) eventKeys.next();
                List<OrderEvent> events = orderLists.get(orderId);
                if (events == null)
                    continue;
                else {
                    boolean processed = false;
                    //Iterate through each order event and invoke the related function
                    for (OrderEvent event : events) {
                        OrderProcess process = functions.get(event.getType());
                        if (process != null) {
                            process.invoke(event.getOrder());
                            event.setProcessed(true);
                            processed = true;
                        } else
                            event.setProcessed(true); //cannot find a handler for this order status type, so set to processed to have the message get deleted
                    }

                    if(processed)
                        System.out.println("Processed ");
                    else
                        System.out.println("Did not process ");

                    System.out.println("order " + orderId);
                }
            }

            return orderLists;
        } catch (JdxServiceException e){
            e.printStackTrace();
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            throw new JdxServiceException("Cannot process order events " + e.getMessage());
        }
    }

    /**
     * Iterate through all messages, organize a map of all orders by orderId.
     * @param messages
     * @return
     * @throws JdxServiceException
     */
    public Map<String, List<OrderEvent>> buildOrderLists(List<Message> messages) throws JdxServiceException {
        Map<String, List<OrderEvent>> orders = new HashMap<>();

        try {
            for (Message message : messages) {
        System.out.println("Getting message " + mapper.writeValueAsString(message.getBody()));
                EntityPayload event = null;

                try {
                    event = mapper.convertValue(message.getBody(), EntityPayload.class);
                } catch (Exception e) {
                    System.err.println("Exception converting message: " + e.getMessage());
                }

                if(event == null)
                    continue;

                EventType eventType = event.getEvent();
                if(eventType != null){
                    OrderEvent orderEvent = new OrderEvent();
                    Order order = mapper.convertValue(event.getEntity(), Order.class);
                    if(order != null) {
                        orderEvent.setOrder(order);
                        orderEvent.setType(order.getCurrentStatus());
                    }

                    orderEvent.setMessageId(message.getMessageId());
                    orderEvent.setProcessed(false);

                    List<OrderEvent> foundEvents = orders.get(order.getId());
                    if(foundEvents != null) {
                        if(foundEvents.size() == 0)
                            foundEvents.add(orderEvent);
                        else
                            foundEvents.add(orderEvent);
                    } else {
                        foundEvents = new ArrayList<>();
                        foundEvents.add(orderEvent);
                        orders.put(order.getId(), foundEvents);
                    }

                }
            }

            return orders;
        } catch (JdxServiceException e){
            e.printStackTrace();
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            throw new JdxServiceException("Cannot process orders from message list");
        }
    }

    public void deleteProcessedMessages(Map<String, List<OrderEvent>> events, AmazonSQS sqs, List<Message> messages) throws JdxServiceException {
        try {
            Iterator eventKeys = null;

            //Iterate through each unique orderId
            if(events != null)
                if(events.keySet() != null)
                    eventKeys = events.keySet().iterator();

            while (eventKeys != null && eventKeys.hasNext()) {
                String orderId = (String) eventKeys.next();
                List<OrderEvent> orderEvents = events.get(orderId);
                if (orderEvents == null)
                    continue;
                else {
                    for(OrderEvent event : orderEvents){
                        if(event.isProcessed()) {
                            Optional<Message> m = messages.stream().filter(x->x.getMessageId().equals(event.getMessageId())).findAny();
                            if(m.isPresent())
                                //sqs.deleteMessage(queueUrl, m.get().getReceiptHandle());
                                System.out.println("Would have deleted " + m.get().getMessageId());
                        }
                    }
                }
            }
        } catch (JdxServiceException e){
            e.printStackTrace();
            throw e;
        }
    }
}
