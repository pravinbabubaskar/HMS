package nju.quadra.hms.bl;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import nju.quadra.hms.blservice.OrderBLService;
import nju.quadra.hms.data.mysql.CreditDataServiceImpl;
import nju.quadra.hms.data.mysql.OrderDataServiceImpl;
import nju.quadra.hms.dataservice.CreditDataService;
import nju.quadra.hms.dataservice.OrderDataService;
import nju.quadra.hms.model.CreditAction;
import nju.quadra.hms.model.MemberType;
import nju.quadra.hms.model.OrderState;
import nju.quadra.hms.model.ResultMessage;
import nju.quadra.hms.po.CreditRecordPO;
import nju.quadra.hms.po.HotelPromotionPO;
import nju.quadra.hms.po.OrderPO;
import nju.quadra.hms.po.WebsitePromotionPO;
import nju.quadra.hms.vo.*;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;

/**
 * Created by RaUkonn on 2016/11/20.
 */
public class OrderBL implements OrderBLService {
    private OrderDataService orderDataService = new OrderDataServiceImpl();

    @Override
    public PriceVO getPrice(OrderVO vo) {
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
        HotelRoomVO room = new HotelRoomBL().getById(vo.roomId);
        if (room == null) {
            return new PriceVO("客房类型不存在，请重新选择");
        } else {
            int roomCount = room.total;
            ArrayList<OrderVO> orders = getByHotel(vo.hotelId);
            roomCount -= orders.stream()
                    .filter(order -> !order.state.equals(OrderState.FINISHED)
                            && !order.state.equals(OrderState.RANKED)
                            && !order.state.equals(OrderState.UNDO)
                            && order.roomId == vo.roomId
                            && order.startDate.compareTo(vo.endDate) < 0)
                    .mapToInt(order -> order.roomCount).sum();
            if (roomCount < vo.roomCount) {
                return new PriceVO("房间数量不足");
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
        // TODO check website promotion
        WebsitePromotionVO websitePromotion = null;

        double finalPrice = originalPrice * (hotelPromotion != null ? hotelPromotion.promotion : 1.0)
                * (websitePromotion != null ? websitePromotion.promotion : 1.0);
        return new PriceVO(originalPrice, finalPrice, hotelPromotion, websitePromotion);
    }

    @Override
    public ResultMessage add(OrderVO vo) {
        try {
            PriceVO priceVO = getPrice(vo);
            if (priceVO.result.result != ResultMessage.RESULT_SUCCESS) {
                return priceVO.result;
            }
            if (Double.compare(priceVO.finalPrice, vo.price) != 0) {
                return new ResultMessage(ResultMessage.RESULT_GENERAL_ERROR, "订单价格发生变化，请重新预订");
            }
            OrderPO po = OrderBL.toPO(vo);
            orderDataService.insert(po);
        } catch (Exception e) {
            e.printStackTrace();
            return new ResultMessage(ResultMessage.RESULT_DB_ERROR);
        }
        return new ResultMessage(ResultMessage.RESULT_SUCCESS);
    }

    @Override
    public ArrayList<OrderVO> getByCustomer(String username) {
        ArrayList<OrderVO> voarr = new ArrayList<>();
        try {
            ArrayList<OrderPO> poarr = orderDataService.getByCustomer(username);
            for(OrderPO po: poarr) {
                voarr.add(OrderBL.toVO(po));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return voarr;
    }

    @Override
    public ArrayList<OrderVO> getByHotel(int hotelId) {
        ArrayList<OrderVO> voarr = new ArrayList<>();
        try {
            ArrayList<OrderPO> poarr = orderDataService.getByHotel(hotelId);
            for(OrderPO po: poarr) {
                voarr.add(OrderBL.toVO(po));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return voarr;
    }

    @Override
    public ArrayList<OrderVO> getByState(OrderState state) {
        ArrayList<OrderVO> voarr = new ArrayList<>();
        try {
            ArrayList<OrderPO> poarr = orderDataService.getByState(state);
            for(OrderPO po: poarr) {
                voarr.add(OrderBL.toVO(po));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return voarr;
    }

    @Override
    public ResultMessage undoDelayed(OrderVO vo, boolean returnAllCredit) {
        try {
            CreditDataService creditDataService = new CreditDataServiceImpl();
            OrderPO po = orderDataService.getById(vo.id);
             if(po.getState() != OrderState.DELAYED)
                 //订单状态必须为"异常(逾期)"才可调用此方法
                 return new ResultMessage(ResultMessage.RESULT_GENERAL_ERROR, "该订单无法被撤销（订单状态不为\"异常(逾期)\"），请重新选择");
             po.setState(OrderState.UNDO);
             //todo:这里还要加个记录撤销时间的东西，表打错了orz
             orderDataService.update(po);
             //增添的信用值为订单的原价或者一半
            double currRate = returnAllCredit? CreditRecordBL.UNDO_DELAYED_RATE[1]: CreditRecordBL.UNDO_DELAYED_RATE[0];
             CreditRecordPO creditRecordPO = new CreditRecordPO(0, vo.username, LocalDateTime.now(), vo.id, CreditAction.ORDER_UNDO, vo.price * currRate);
             creditDataService.insert(creditRecordPO);
        } catch (NullPointerException e) {
            e.printStackTrace();
            return new ResultMessage(ResultMessage.RESULT_GENERAL_ERROR, "订单不存在，请确认订单信息");
        } catch (Exception e) {
            e.printStackTrace();
            return new ResultMessage(ResultMessage.RESULT_GENERAL_ERROR, "服务器访问异常，请重新尝试");
        }
        return new ResultMessage(ResultMessage.RESULT_SUCCESS);
    }

    @Override
    public ResultMessage undoUnfinished(OrderVO vo) {
        try {
            CreditDataService creditDataService = new CreditDataServiceImpl();
            OrderPO po = orderDataService.getById(vo.id);
            //订单状态必须为"未执行"才可调用此方法
            if(po.getState() != OrderState.BOOKED)
                return new ResultMessage(ResultMessage.RESULT_GENERAL_ERROR, "该订单无法被撤销（订单状态不为\"未执行\"），请重新选择");
            po.setState(OrderState.UNDO);
            orderDataService.update(po);
            //如果撤销的订单距离最晚订单执行时间不足6个小时，撤销的同时扣除用户的信用值，信用值为订单的(总价值*1/2)
            double currRate = 0;
            LocalDate latestAvaliableTime = vo.startDate.plus(Duration.ofHours(CreditRecordBL.LATEST_CHECKIN_TIME_GAP));
            if(LocalDate.now().compareTo(latestAvaliableTime) > 0)
                currRate = CreditRecordBL.UNDO_UNFINISHED_RATE;
            CreditRecordPO creditRecordPO = new CreditRecordPO(0, vo.username, LocalDateTime.now(), vo.id, CreditAction.ORDER_UNDO, vo.price * currRate);
            creditDataService.insert(creditRecordPO);
        } catch (NullPointerException e) {
            e.printStackTrace();
            return new ResultMessage(ResultMessage.RESULT_GENERAL_ERROR, "订单不存在，请确认订单信息");
        } catch (Exception e) {
            e.printStackTrace();
            return new ResultMessage(ResultMessage.RESULT_GENERAL_ERROR, "服务器访问异常，请重新尝试");
        }
        return new ResultMessage(ResultMessage.RESULT_SUCCESS);
    }

    @Override
    public ResultMessage finish(OrderVO vo) {
        try {
            CreditDataService creditDataService = new CreditDataServiceImpl();
            OrderPO po = orderDataService.getById(vo.id);
            if(po.getState() == OrderState.FINISHED)
                return new ResultMessage(ResultMessage.RESULT_GENERAL_ERROR, "该订单已经完成，请重新选择");
            po.setState(OrderState.FINISHED);
            po.setEndDate(LocalDate.now());
            orderDataService.update(po);
            //信用值为订单原价
            CreditRecordPO creditRecordPO = new CreditRecordPO(0, vo.username, LocalDateTime.now(), vo.id, CreditAction.ORDER_UNDO, vo.price * CreditRecordBL.FINISH_RATE);
            creditDataService.insert(creditRecordPO);
        } catch (NullPointerException e) {
            e.printStackTrace();
            return new ResultMessage(ResultMessage.RESULT_GENERAL_ERROR, "订单不存在，请确认订单信息");
        } catch (Exception e) {
            e.printStackTrace();
            return new ResultMessage(ResultMessage.RESULT_GENERAL_ERROR, "服务器访问异常，请重新尝试");
        }
        return new ResultMessage(ResultMessage.RESULT_SUCCESS);
    }

    @Override
    public ResultMessage addRank(OrderRankVO vo) {
        try {
            OrderPO po = orderDataService.getById(vo.orderId);
            po.setRank(vo.rank);
            po.setComment(vo.comment);
            orderDataService.update(po);
        } catch (NullPointerException e) {
            e.printStackTrace();
            return new ResultMessage(ResultMessage.RESULT_GENERAL_ERROR, "订单不存在，请确认订单信息");
        } catch (Exception e) {
            e.printStackTrace();
            return new ResultMessage(ResultMessage.RESULT_GENERAL_ERROR, "服务器访问异常，请重新尝试");
        }
        return new ResultMessage(ResultMessage.RESULT_SUCCESS);
    }

    public static OrderVO toVO(OrderPO po) {
        return new OrderVO(po.getId(), po.getUsername(), po.getHotelId(), po.getStartDate(), po.getEndDate(), po.getRoomId(), po.getRoomCount(), po.getPersonCount(), new Gson().fromJson(po.getPersons(), new TypeToken<ArrayList<String>>(){}.getType()), po.isHasChildren(), po.getPrice(), po.getState(), po.getRank(), po.getComment());
    }

    public static OrderPO toPO(OrderVO vo) {
        return new OrderPO(vo.id, vo.username, vo.hotelId, vo.startDate, vo.endDate, vo.roomId, vo.roomCount, vo.personCount, new Gson().toJson(vo.persons), vo.hasChildren, vo.price, vo.state, vo.rank, vo.comment);
    }

}
