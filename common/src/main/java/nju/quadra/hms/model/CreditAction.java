package nju.quadra.hms.model;

public enum CreditAction {
    ORIGINAL("初始信用值"),
    ORDER_FINISHED("完成订单"),
    ORDER_CANCELLED("撤销未执行订单"),
    ORDER_DELAYED("订单逾期未执行"),
    ORDER_UNDO("撤销异常订单返还"),
    CREDIT_TOPUP("信用充值");

    final String showname;

    CreditAction(String showname) {
        this.showname = showname;
    }

    @Override
    public String toString() {
        return showname;
    }

    public static CreditAction getById(int id) {
        return CreditAction.values()[id];
    }
}
