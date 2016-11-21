package nju.quadra.hms.bl;

import nju.quadra.hms.bl.mockObject.MockOrderBL;
import nju.quadra.hms.blservice.orderBL.OrderBLService;
import nju.quadra.hms.data.mysql.OrderDataServiceImpl;
import nju.quadra.hms.dataservice.OrderDataService;
import nju.quadra.hms.model.OrderState;
import nju.quadra.hms.model.ResultMessage;
import nju.quadra.hms.po.OrderPO;
import nju.quadra.hms.vo.OrderRankVO;
import nju.quadra.hms.vo.OrderVO;
import nju.quadra.hms.vo.PriceVO;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.ArrayList;
import java.util.Date;

import static org.junit.Assert.*;


/**
 * Created by admin on 2016/11/6.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class OrderBLTest {
    OrderBLService orderBL;
    OrderDataService orderDataService;

    @Before
    public void init() {
        orderBL = new OrderBL();
        orderDataService = new OrderDataServiceImpl();
    }

    @Test
    public void test1_Add() {
        ArrayList<String> persons1 = new ArrayList<>();
        persons1.add("TEST|person11");
        persons1.add("TEST|person12");
        ArrayList<String> persons2 = new ArrayList<>();
        persons2.add("TEST|person21");
        persons2.add("TEST|person22");
        persons2.add("TEST|person23");
        OrderVO vo1 = new OrderVO(0, "TEST|username1", 123456, new Date(2222-1900, 11-1, 21+1), new Date(2222-1900, 11-1 , 23+1), 111, 1, 2, persons1, false, 299.0, OrderState.UNCOMPLETED, 0, null);
        OrderVO vo2 = new OrderVO(0, "TEST|username2", 123456, new Date(2016-1900, 11-1, 22+1), new Date(2016-1900, 11-1 , 23+1), 222, 1, 3, persons2, true, 599.0, OrderState.UNCOMPLETED, 0, null);
        orderBL.add(vo1);
        orderBL.add(vo2);
    }

    @Test
    public void test2_GetByCustomer() {
        ArrayList<OrderVO> voarr1 = orderBL.getByCustomer("TEST|username1");
        ArrayList<OrderVO> voarr2 = orderBL.getByCustomer("TEST|username2");

        assertEquals(123456, voarr1.get(0).hotelId);
        assertEquals(1, voarr2.size());
    }

    @Test
    public void test3_GetByState() {
        ArrayList<OrderVO> voarr = orderBL.getByState(OrderState.UNCOMPLETED);
        assertEquals(2, voarr.size());
    }

    @Test
    public void test4_GetByHotel() {
        ArrayList<OrderVO> voarr = orderBL.getByHotel(123456);
        assertEquals(2, voarr.size());
    }

    @Test
    public void test5_GetPrice() {
        ArrayList<OrderVO> voarr = orderBL.getByState(OrderState.UNCOMPLETED);
        PriceVO pricevo1 = orderBL.getPrice(voarr.get(0));
        assertEquals(299.0, pricevo1.finalPrice, 1.0);
        PriceVO pricevo2 = orderBL.getPrice(voarr.get(1));
        assertEquals(599.0, pricevo2.originalPrice, 1.0);
    }

    @Test
    public void test6_DeleteAll() {
        try {
            ArrayList<OrderPO> poarr = orderDataService.getByHotel(123456);
            for(OrderPO po: poarr) orderDataService.delete(po);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    //todo:由于CreditRecordBL部分没写完。。。后面没法测试
}
