package com.bencodez.advancedcore.api.messages;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.HoverEvent;

public interface HoverEventSupport {

	HoverEvent createHoverEvent(BaseComponent[] value);

	static HoverEventSupport findInstance() {
		return new HoverEventSupport15();

	}
}
