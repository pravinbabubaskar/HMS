package nju.quadra.hms.model;

public enum MemberType {
    NONE("非会员"),
    PERSONAL("个人会员"),
    COMPANY("企业会员");

    private final String showname;

    MemberType(String showname) {
        this.showname = showname;
    }

    @Override
    public String toString() {
        return showname;
    }

    public static MemberType getById(int id) {
        return MemberType.values()[id];
    }
    public static MemberType getByShowname(String showname) {
        MemberType[] memberTypes = MemberType.values();
        for(MemberType ut: memberTypes) {
            if(ut.showname.equals(showname))
                return ut;
        }
        return null;
    }

}
