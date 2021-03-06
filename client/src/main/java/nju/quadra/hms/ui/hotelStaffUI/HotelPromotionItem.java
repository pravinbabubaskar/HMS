package nju.quadra.hms.ui.hotelStaffUI;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import nju.quadra.hms.controller.HotelStaffController;
import nju.quadra.hms.model.ResultMessage;
import nju.quadra.hms.ui.common.Dialogs;
import nju.quadra.hms.vo.HotelPromotionVO;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

class HotelPromotionItem extends Parent {

    private HotelStaffController controller;
    private HotelPromotionVO vo;
    private HotelPromotionView parent;

    @FXML
    private Label labelDate, labelName;

    public HotelPromotionItem(HotelPromotionView parent, HotelPromotionVO vo, HotelStaffController controller) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("promotionitem.fxml"));
        loader.setController(this);
        this.getChildren().add(loader.load());

        this.parent = parent;
        this.vo = vo;
        this.controller = controller;
        if (vo != null) {
            labelDate.setText(vo.startTime.format(DateTimeFormatter.ofPattern("uuuu/MM/dd")) + " - " + vo.endTime.format(DateTimeFormatter.ofPattern("uuuu/MM/dd")));
            labelName.setText(vo.name);
        }
    }

    @FXML
    protected void onDetailAction() throws IOException {
        parent.loadView(new HotelPromotionEditView(vo, controller, true, parent::loadPromotion));
    }

    @FXML
    protected void onModifyAction() throws IOException {
        parent.loadView(new HotelPromotionEditView(vo, controller, false, parent::loadPromotion));
    }

    @FXML
    protected void onDeleteAction() throws IOException {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("确认");
        alert.setHeaderText(null);
        alert.setContentText("是否确认删除此促销策略?");
        alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
        Optional<ButtonType> confirm = alert.showAndWait();
        if (confirm.isPresent() && confirm.get().equals(ButtonType.YES)) {
            ResultMessage result = controller.deleteHotelPromotion(this.vo.id);
            if (result.result == ResultMessage.RESULT_SUCCESS) {
                Dialogs.showInfo("删除促销策略成功");
            } else {
                Dialogs.showError("删除促销策略失败: " + result.message);
            }
            parent.loadPromotion();
        }
    }

}
