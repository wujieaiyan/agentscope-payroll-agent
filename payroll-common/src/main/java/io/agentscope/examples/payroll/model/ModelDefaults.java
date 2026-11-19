package io.agentscope.examples.payroll.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** 三个使用面的默认连接 ID；空串表示未配置。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ModelDefaults(String control, String coding, String utility) {

    public static final ModelDefaults EMPTY = new ModelDefaults("", "", "");

    public ModelDefaults {
        control = control == null ? "" : control.trim();
        coding = coding == null ? "" : coding.trim();
        utility = utility == null ? "" : utility.trim();
    }

    public String forPlane(String plane) {
        return switch (plane) {
            case "control" -> control;
            case "coding" -> coding;
            case "utility" -> utility;
            default -> "";
        };
    }
}
