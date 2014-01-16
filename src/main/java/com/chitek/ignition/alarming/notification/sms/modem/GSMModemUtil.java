package com.chitek.ignition.alarming.notification.sms.modem;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import com.chitek.ignition.alarming.notification.sms.SmsNotification;
import com.inductiveautomation.ignition.common.BundleUtil;

public class GSMModemUtil {
	
	/**
	 * Returns the error text for a given CME ERROR code
	 * @param errorCode
	 * @return
	 */
	public static String getErrorText(int errorCode) {
		String text = BundleUtil.get().getString(String.format("%s.cme_error.%d", SmsNotification.class.getSimpleName(), errorCode));
		if (text == null) {
			return BundleUtil.get().getString(String.format("%s.cme_error.unknown", SmsNotification.class.getSimpleName()), errorCode);
		} else {
			return text;
		}
	}
			
	/**
	 * Expand the given value range to a List of integers.<br />
	 * <pre>
	 * Examples:
	 * (0) Only the value 0 is supported.
	 * (1,2,3) The values 1, 2, and 3 are supported.
	 * (1-3) The values 1 through 3 are supported.
	 * (0,4,5,6,9,11,12) The several listed values are supported.
	 * (0,4-6,9,11-12) An alternative expression of the above list.</pre>
	 * @param val
	 * @return
	 */
	public static List<Integer> ExpandRangeResponse(String val) {
		List<Integer>result = new ArrayList<Integer>(5);
		StringTokenizer tokenizer = new StringTokenizer(val,",");
		while (tokenizer.hasMoreTokens()) {
			String token = tokenizer.nextToken();
			if (token.indexOf("-") > -1) {
				String[] vals = token.split("-");
				for (int i = Integer.parseInt(vals[0]); i <= Integer.parseInt(vals[1]); i++) {
					result.add(i);
				}
			} else {
				result.add(Integer.parseInt(token));
			}
		}
		return result;
	}
}
