package com.coflnet;

import CoflCore.commands.models.ChatMessageData;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import java.net.URI;

public class Utils {
    public static  MutableComponent ChatComponent(ChatMessageData cmd) {
        MutableComponent message = Component.literal(cmd.Text);
        if (cmd.OnClick != null) {
            if (cmd.OnClick.startsWith("http")) {
                message.withStyle((style) -> {
                    try {
                        return style.withClickEvent(new ClickEvent.OpenUrl(URI.create(cmd.OnClick)));
                    } catch (Exception e) {
                        System.out.println("Error occurred while parsing URI");
                        return style;
                    }
                });
            }
            else if(cmd.OnClick.startsWith("suggest:")){
                String suggestion = cmd.OnClick.substring("suggest:".length());
                message.withStyle((style) -> style.withClickEvent(new ClickEvent.SuggestCommand(suggestion)));
            } else if(cmd.OnClick.startsWith("copy:")) {
                String copyText = cmd.OnClick.substring("copy:".length());
                message.withStyle((style) -> style.withClickEvent(new ClickEvent.CopyToClipboard(copyText)));
            } else {
                message.withStyle((style) -> style.withClickEvent(new ClickEvent.RunCommand(cmd.OnClick)));
            }
        }

        if (cmd.Hover != null && !cmd.Hover.isEmpty()) {
            message.withStyle((style) -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(cmd.Hover))));
        }
        return message;
    }
}
