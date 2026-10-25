package com.coflnet.models;

/**
 * JSON model for interactive text elements
 */
public class TextElement {
    public String text;
    public String hover;
    public String onClick;
    
    public TextElement() {}
    
    public TextElement(String text, String hover, String onClick) {
        this.text = text;
        this.hover = hover;
        this.onClick = onClick;
    }
}
