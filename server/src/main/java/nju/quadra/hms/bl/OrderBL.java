package nju.quadra.hms.bl;

import nju.quadra.hms.util.Logger;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import nju.quadra.hms.blservice.CreditRecordBLService;
import nju.quadra.hms.blservice.OrderBLService;
import nju.quadra.hms.data.mysql.OrderDataServiceImpl;
import nju.quadra.hms.dataservice.OrderDataService;
import nju.quadra.hms.model.*;
import nju.quadra.hms.po.OrderPO;
import nju.quadra.hms.vo.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;

import static nju.quadra.hms.bl.CreditRecordBL.LATEST_CHECKIN_TIME_GAP;

public class OrderBL implements OrderBLService {
    private final LoginSession session;
    private final HotelVO hotel;
    private final OrderDataService orderDataService = new OrderDataServiceImpl();

    public OrderBL() {
        session = null;
        hotel = null;
        // "自动"检查异常订单
        checkDelayed();
    }

    public OrderBL(LoginSession session) {
        this.session = session;
        hotel = new HotelBL().getByStaff(session.username);
        checkDelayed();
    }

    @Override
    public PriceVO getPrice(OrderVO vo) {
        // 安全性: 仅限客户调用
        if (session != null) {
            if (session.userType.equals(UserType.CUSTOMER)) {
                vo.username = session.username;
            } else {
                return new PriceVO("非法访问");
            }
        }
        // "自动"检查异常订单
        checkDelayed();
        // check order
        if (vo.startDate == null || vo.endDate == null || vo.roomCount <= 0
                || vo.persons == null || vo.persons.size() == 0) {
            return new PriceVO("订单信息不完整，请重新输入");
        }
        // check credit and date
        ArrayList<CreditRecordVO> credits = new CreditRecordBL().get(vo.username);
        if (credits.size() > 0 && credits.get(0).creditResult < CreditRecordBL.MIN_CREDIT) {
            return new PriceVO("用户信用值不足，不能预订酒店");
        }
        if (vo.startDate.compareTo(vo.endDate) >= 0) {
            return new PriceVO("入住时间应早于离开时间，请重新输入");
        }
        if (vo.startDate.compareTo(LocalDate.now()) < 0) {
            return new PriceVO("入住时间早于当前时间，请重新输入");
        }

        // check room count
        HotelRoomVO room = new HotelRoomBL().getById(vo.roomId);
        if (room == null || room.hotelId != vo.hotelId) {
            return new PriceVO("客房类型不存在，请重新选择");
        } else {
            int roomCount = room.total;
            ArrayList<OrderDetailVO> orders = getByHotel(vo.hotelId);
            roomCount -= orders.stream()
                    .filter(order -> !order.state.equals(OrderState.FINISHED)
                            && !order.state.equals(OrderState.RANKED)
                            && !order.state.equals(OrderState.UNDO)
                            && order.roomId == vo.roomId
                            && order.startDate.compareTo(vo.endDate) < 0)
                    .mapToInt(order -> order.roomCount).sum();
            if (roomCount < vo.roomCount) {
                return new PriceVO("客房数量不足");
            }
        }

        double originalPrice = vo.roomCount * room.price * vo.endDate.compareTo(vo.startDate);
        // check hotel promotion
        HotelPromotionVO hotelPromotion = null;
        ArrayList<HotelPromotionVO> hpvos = new HotelPromotionBL().get(vo.hotelId);
        for (HotelPromotionVO promo : hpvos) {
            boolean available = false;
            switch (promo.type) {
                case MULTI_PROMOTION:
                    if (vo.roomCount >= 3) { //三间或以上优惠
                        available = true;
                    }
                    break;
                case BIRTHDAY_PROMOTION:
                    MemberVO member = new CustomerBL().getMemberInfo(vo.username);
                    if (member.memberType.equals(MemberType.PERSONAL)
                            && member.birthday.getMonthValue() == LocalDate.now().getMonthValue()
                            && member.birthday.getDayOfMonth() == LocalDate.now().getDayOfMonth()) {
                        available = true;
                    }
                    break;
                case COMPANY_PROMOTION:
                    MemberVO member2 = new CustomerBL().getMemberInfo(vo.username);
                    if (member2.memberType.equals(MemberType.COMPANY) && promo.cooperation.contains(member2.companyName)) {
                        available = true;
                    }
                    break;
            }
            if (promo.startTime.compareTo(LocalDate.now()) > 0 || promo.endTime.compareTo(LocalDate.now()) < 0) {
                available = false;
            }
            if (available && (hotelPromotion == null || promo.promotion < hotelPromotion.promotion)) {
                hotelPromotion = promo;
            }
        }

        // check website promotion
        WebsitePromotionVO websitePromotion = null;
        ArrayList<WebsitePromotionVO> wpvos = new WebsitePromotionBL().get();
        for (WebsitePromotionVO promo : wpvos) {
            if (promo.type.equals(WebsitePromotionType.LEVEL_PROMOTION)) {
                HotelVO hotel = new HotelBL().getDetail(vo.hotelId);
                if (promo.areaId > 0 && hotel.areaId != promo.areaId) {
                    continue;
                }
                for (double credit : promo.memberLevel.keySet()) {
                    if (credits.get(0).creditResult >= credit && promo.memberLevel.get(credit) < promo.promotion) {
                        promo.promotion = promo.memberLevel.get(credit);
                    }
                }
            }
            if (promo.startTime.compareTo(LocalDate.now()) > 0 || promo.endTime.compareTo(LocalDate.now()) < 0) {
                continue;
            }
            if (websitePromotion == null || promo.promotion < websitePromotion.promotion) {
                websitePromotion = promo;
            }
        }

        // calculate final price
        double finalPrice = originalPrice * (hotelPromotion != null ? hotelPromotion.promotion : 1.0)
                * (websitePromotion != null ? websitePromotion.promotion : 1.0);
        return new PriceVO(originalPrice, finalPrice, hotelPromotion, websitePromotion);
    }

    @Override
    public ResultMessage add(OrderVO vo) {
        // 安全性: 仅限客户访问
        if (session != null) {
            if (session.userType.equals(UserType.CUSTOMER)) {
                vo.username = session.username;
            } else {
                return null;
            }
        }
        // "自动"检查异常订单
        checkDelayed();

        try {
            PriceVO priceVO = getPrice(vo);
            if (priceVO.result.result != ResultMessage.RESULT_SUCCESS) {
                return priceVO.result;
            }
            if (Double.compare(priceVO.finalPrice, vo.price) != 0) {
                return new ResultMessage("订单价格发生变化，请重新预订");
            }
            vo.state = OrderState.BOOKED;
            OrderPO po = OrderBL.toPO(vo);
            orderDataService.insert(po);
        } catch (Exception e) {
            Logger.log(e);
            return new ResultMessage(ResultMessage.RESULT_DB_ERROR);
        }
        return new ResultMessage(ResultMessage.RESULT_SUCCESS);
    }

    @Override
    public ArrayList<OrderDetailVO> getByCustomer(String username) {
        // 安全性: 仅限客户访问自己的订单
        if (session != null) {
            if (session.userType.equals(UserType.CUSTOMER)) {
                username = session.username;
            } else {
                return new ArrayList<>();
            }
        }
        // "自动"检查异常订单
        checkDelayed();
        ArrayList<OrderDetailVO> voarr = new ArrayList<>();
        try {
            ArrayList<OrderPO> poarr = orderDataService.getByCustomer(username);
            for(OrderPO po: poarr) {
                voarr.add(toDetailVO(po));
            }
        } catch (Exception e) {
            Logger.log(e);
        }
        return voarr;
    }

    @Override
    public ArrayList<OrderDetailVO> getByHotel(int hotelId) {
        // 安全性: 仅限酒店工作人与访问自己酒店的订单
        if (session != null) {
            if (session.userType.equals(UserType.HOTEL_STAFF)) {
                hotelId = hotel.id;
            } else {
                return new ArrayList<>();
            }
        }
        // "自动"检查异常订单
        checkDelayed();
        ArrayList<OrderDetailVO> voarr = new ArrayList<>();
        try {
            ArrayList<OrderPO> poarr = orderDataService.getByHotel(hotelId);
            for(OrderPO po: poarr) {
                voarr.add(toDetailVO(po));
            }
        } catch (Exception e) {
            Logger.log(e);
        }
        return voarr;
    }

    @Override
    public ArrayList<OrderDetailVO> getByState(OrderState state) {
        // 安全性: 仅限网站营销人员访问
        if (session != null && !session.userType.equals(UserType.WEBSITE_MARKETER)) {
            return new ArrayList<>();
        }
        // "自动"检查异常订单
        checkDelayed();
        ArrayList<OrderDetailVO> voarr = new ArrayList<>();
        try {
            ArrayList<OrderPO> poarr = orderDataService.getByState(state);
            for(OrderPO po: poarr) {
                voarr.add(toDetailVO(po));
            }
        } catch (Exception e) {
            Logger.log(e);
        }
        return voarr;
    }

    @Override
    public ResultMessage undoDelayed(int orderId, boolean returnAllCredit) {
        // 安全性: 仅限网站营销人员访问
        if (session != null && !session.userType.equals(UserType.WEBSITE_MARKETER)) {
            return new ResultMessage(ResultMessage.RESULT_ACCESS_DENIED);
        }
        // "自动"检查异常订单
        checkDelayed();
        try {
            OrderPO po = orderDataService.getById(orderId);
            // 订单状态必须为"异常"才可调用此方法
            if (po.getState() != OrderState.DELAYED) {
                return new ResultMessage("该订单无法被撤销（订单状态不为\"异常\"）");
            }
            po.setState(OrderState.UNDO);
            orderDataService.update(po);
            // 增添的信用值为订单的原价或者一半
            double currRate = returnAllCredit ? CreditRecordBL.UNDO_DELAYED_RATE[1] : CreditRecordBL.UNDO_DELAYED_RATE[0];
            new CreditRecordBL().add(new CreditRecordVO(0, po.getUsername(), null, orderId, CreditAction.ORDER_UNDO, po.getPrice() * currRate, 0));
        } catch (NullPointerException e) {
            Logger.log(e);
            return new ResultMessage("订单不存在，请确认订单信息");
        } catch (Exception e) {
            Logger.log(e);
            return new ResultMessage("服务器访问异常，请重新尝试");
        }
        return new ResultMessage(ResultMessage.RESULT_SUCCESS);
    }

    @Override
    public ResultMessage undoUnfinished(int orderId) {
        // "自动"检查异常订单
        checkDelayed();
        try {
            OrderPO po = orderDataService.getById(orderId);
            // 安全性: 仅允许撤销自己的订单
            if (session != null && (!session.userType.equals(UserType.CUSTOMER) || !po.getUsername().equals(session.username))) {
                return new ResultMessage(ResultMessage.RESULT_ACCESS_DENIED);
            }
            // 订单状态必须为"未执行"才可调用此方法
            if (po.getState() != OrderState.BOOKED) {
                return new ResultMessage("该订单无法被撤销（订单状态不为\"未执行\"）");
            }
            po.setState(OrderState.UNDO);
            orderDataService.update(po);
            // 如果撤销的订单距离最晚订单执行时间不足6个小时，撤销的同时扣除用户的信用值
            LocalDateTime latestAvaliableTime = LocalDateTime.of(po.getStartDate(), LocalTime.of(24 - LATEST_CHECKIN_TIME_GAP, 0));
            if (LocalDateTime.now().compareTo(latestAvaliableTime) > 0) {
                new CreditRecordBL().add(new CreditRecordVO(0, po.getUsername(), null, orderId, CreditAction.ORDER_CANCELLED, po.getPrice() * CreditRecordBL.UNDO_UNFINISHED_RATE, 0));
            }
        } catch (NullPointerException e) {
            Logger.log(e);
            return new ResultMessage("订单不存在，请确认订单信息");
        } catch (Exception e) {
            Logger.log(e);
            return new ResultMessage("服务器访问异常，请重新尝试");
        }
        return new ResultMessage(ResultMessage.RESULT_SUCCESS);
    }

    @Override
    public ResultMessage checkin(int orderId) {
        // "自动"检查异常订单
        checkDelayed();
        try {
            OrderPO po = orderDataService.getById(orderId);
            // 安全性: 仅允许操作对应酒店的订单
            if (session != null && (!session.userType.equals(UserType.HOTEL_STAFF) || po.getHotelId() != hotel.id)) {
                return new ResultMessage(ResultMessage.RESULT_ACCESS_DENIED);
            }
            if (po.getState() != OrderState.BOOKED && po.getState() != OrderState.DELAYED) {
                return new ResultMessage("该订单无法办理入住");
            }
            po.setStartDate(LocalDate.now());
            po.setState(OrderState.UNFINISHED);
            orderDataService.update(po);
            // 返还信用值
            CreditRecordBLService creditBL = new CreditRecordBL();
            if (po.getState().equals(OrderState.DELAYED)) {
                creditBL.add(new CreditRecordVO(0, po.getUsername(), null, orderId, CreditAction.ORDER_UNDO, po.getPrice() * CreditRecordBL.FINISH_RATE, 0));
            }
            // 增加信用值
            creditBL.add(new CreditRecordVO(0, po.getUsername(), null, orderId, CreditAction.ORDER_FINISHED, po.getPrice() * CreditRecordBL.FINISH_RATE, 0));
        } catch (NullPointerException e) {
            Logger.log(e);
            return new ResultMessage("订单不存在，请确认订单信息");
        } catch (Exception e) {
            Logger.log(e);
            return new ResultMessage("服务器访问异常，请重新尝试");
        }
        return new ResultMessage(ResultMessage.RESULT_SUCCESS);
    }

    @Override
    public ResultMessage checkout(int orderId) {
        checkDelayed();
        try {
            OrderPO po = orderDataService.getById(orderId);
            // 安全性: 仅允许操作对应酒店的订单
            if (session != null && (!session.userType.equals(UserType.HOTEL_STAFF) || po.getHotelId() != hotel.id)) {
                return new ResultMessage(ResultMessage.RESULT_ACCESS_DENIED);
            }
            if (po.getState() != OrderState.UNFINISHED) {
                return new ResultMessage("该订单未入住，无法退房");
            }
            po.setEndDate(LocalDate.now());
            po.setState(OrderState.FINISHED);
            orderDataService.update(po);
        } catch (NullPointerException e) {
            Logger.log(e);
            return new ResultMessage("订单不存在，请确认订单信息");
        } catch (Exception e) {
            Logger.log(e);
            return new ResultMessage("服务器访问异常，请重新尝试");
        }
        return new ResultMessage(ResultMessage.RESULT_SUCCESS);
    }

    @Override
    public ResultMessage addRank(OrderRankVO vo) {
        try {
            OrderPO po = orderDataService.getById(vo.orderId);
            // 安全性: 仅允许评价自己的订单
            if (session != null && (!session.userType.equals(UserType.CUSTOMER) || !po.getUsername().equals(session.username))) {
                return new ResultMessage(ResultMessage.RESULT_ACCESS_DENIED);
            }
            if (vo.comment == null || vo.comment.isEmpty()) {
                return new ResultMessage("评价内容不能为空，请重新输入");
            }
            if (!po.getState().equals(OrderState.FINISHED)) {
                return new ResultMessage("该订单无法评价");
            }
            po.setState(OrderState.RANKED);
            po.setRank(vo.rank);
            po.setComment(vo.comment);
            orderDataService.update(po);
        } catch (NullPointerException e) {
            Logger.log(e);
            return new ResultMessage("订单不存在，请确认订单信息");
        } catch (Exception e) {
            Logger.log(e);
            return new ResultMessage("服务器访问异常，请重新尝试");
        }
        return new ResultMessage(ResultMessage.RESULT_SUCCESS);
    }

    private void checkDelayed() {
        try {
            ArrayList<OrderPO> orders = orderDataService.getByState(OrderState.BOOKED);
            for (OrderPO order : orders) {
                if (LocalDate.now().compareTo(order.getStartDate()) > 0) {
                    order.setState(OrderState.DELAYED);
                    orderDataService.update(order);
                    new CreditRecordBL().add(new CreditRecordVO(0, order.getUsername(), null, order.getId(), CreditAction.ORDER_DELAYED, order.getPrice() * CreditRecordBL.DELAYED_RATE, 0));
                }
            }
        } catch (Exception e) {
            Logger.log(e);
        }
    }

    public static OrderVO toVO(OrderPO po) {
        return new OrderVO(po.getId(), po.getUsername(), po.getHotelId(), po.getStartDate(), po.getEndDate(), po.getRoomId(), po.getRoomCount(), po.getPersonCount(), new Gson().fromJson(po.getPersons(), new TypeToken<ArrayList<String>>(){}.getType()), po.isHasChildren(), po.getPrice(), po.getState(), po.getRank(), po.getComment());
    }

    private static OrderDetailVO toDetailVO(OrderPO po) {
        HotelVO hotel = new HotelBL().getDetail(po.getHotelId());
        HotelRoomVO room = new HotelRoomBL().getById(po.getRoomId());
        return new OrderDetailVO(po.getId(), po.getUsername(), hotel, po.getStartDate(), po.getEndDate(), room, po.getRoomCount(), new Gson().fromJson(po.getPersons(), new TypeToken<ArrayList<String>>(){}.getType()), po.isHasChildren(), po.getPrice(), po.getState(), po.getRank(), po.getComment());
    }

    private static OrderPO toPO(OrderVO vo) {
        return new OrderPO(vo.id, vo.username, vo.hotelId, vo.startDate, vo.endDate, vo.roomId, vo.roomCount, vo.personCount, new Gson().toJson(vo.persons), vo.hasChildren, vo.price, vo.state, vo.rank, vo.comment);
    }

}
