package com.chitek.ignition.alarming.notification.sms.settings;

import java.util.Locale;

import simpleorm.dataset.SFieldFlags;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfileRecord;
import com.inductiveautomation.ignition.gateway.audit.AuditProfileRecord;
import com.inductiveautomation.ignition.gateway.localdb.persistence.BooleanField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.Category;
import com.inductiveautomation.ignition.gateway.localdb.persistence.EnumField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.IdentityField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.IntField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.LongField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistentRecord;
import com.inductiveautomation.ignition.gateway.localdb.persistence.RecordMeta;
import com.inductiveautomation.ignition.gateway.localdb.persistence.ReferenceField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.StringField;

import org.apache.wicket.validation.validator.RangeValidator;

public class GsmSmsNotificationSettings extends PersistentRecord {

	public static final RecordMeta<GsmSmsNotificationSettings> META = new RecordMeta<GsmSmsNotificationSettings>
		(GsmSmsNotificationSettings.class, "ChiSmsNotificationSettings");

	public static final IdentityField Id = new IdentityField(META);
	public static final LongField ProfileId = new LongField(META, "ProfileId", SFieldFlags.SPRIMARY_KEY);
	public static final ReferenceField<AlarmNotificationProfileRecord> Profile = new ReferenceField<AlarmNotificationProfileRecord>(META, AlarmNotificationProfileRecord.META, "Profile", ProfileId);
	
	public static final StringField HostAddress = new StringField(META, "HostAddress", SFieldFlags.SMANDATORY);
	public static final IntField Port = new IntField(META, "Port", SFieldFlags.SMANDATORY);
	public static final EnumField<TelnetMode> Mode = new EnumField<TelnetMode>(META, "TelnetMode", TelnetMode.class);
	public static final IntField CountryCode = new IntField(META, "CountryCode", SFieldFlags.SMANDATORY);
	public static final StringField SimPin = new StringField(META, "SimPin");
	public static final StringField CSCA = new StringField(META, "CSCA");
	public static final BooleanField TwoWayEnabled = new BooleanField(META, "TwoWayEnabled");
	public static final LongField AuditProfileId = new LongField(META, "AuditProfileId");
	public static final ReferenceField<AuditProfileRecord> AuditProfile = new ReferenceField<AuditProfileRecord>(META, AuditProfileRecord.META, "AuditProfile", AuditProfileId);
	
	public static final Category Modem = new Category("GsmSmsNotificationSettings.Category.Modem", 1).include(HostAddress, Port, Mode);
	public static final Category Settings = new Category("GsmSmsNotificationSettings.Category.Settings", 2).include(SimPin, CountryCode, CSCA, TwoWayEnabled);
	public static final Category Auditing = new Category("GsmSmsNotificationSettings.Category.Audit", 3).include(AuditProfile);
	
	@Override
	public RecordMeta<?> getMeta() {
		return META;
	}
	
	/**
	 * @return
	 * The ID-address or hostname of the modem
	 */
	public String getHostAddress() {
		return getString(HostAddress).trim();
	}

	/**
	 * @return
	 * The port used by the modem
	 */
	public Integer getPort() {
		return getInt(Port);
	}
	
	public TelnetMode getTelnetMode() {
		return getEnum(Mode);
	}
	
	/**
	 * @return
	 * 	The default country code used to normalize phone numbers.
	 */
	public int getCountryCode() {
		return getInt(CountryCode);
	}
	
	/**
	 * @return
	 * The PIN code of the SIM card
	 */
	public String getSimPin() {
		String result = getString(SimPin);
		if (result != null) {
			return result.trim();
		} else {
			return "";
		}
	}
	
	/**
	 * @return
	 * The service center address
	 */
	public String getCsca() {
		String result = getString(CSCA);
		if (result != null) {
			return result.trim();
		} else {
			return "";
		}
	}

	/**
	 * @return
	 * 	<code>true</code> if two way mode (acknowlegde by SMS) is enabled
	 */
	public boolean isTwoWayEnabled() {
		return getBoolean(TwoWayEnabled);
	}
	
	
	/**
	 * @return
	 * 	The name of the selected AuditProfile, or null, if now AuditProfile is selected.
	 */
	public String getAuditProfileName() {
		AuditProfileRecord rec = (AuditProfileRecord) findReference(AuditProfile);
		return rec == null ? null : rec.getName();
	}
	
	static {
		Profile.getFormMeta().setVisible(false);
		Mode.setDefault(TelnetMode.Binary);
		CountryCode.addValidator(new RangeValidator<Integer>(1,2000));
		CountryCode.setDefault(PhoneNumberUtil.getInstance().getCountryCodeForRegion(Locale.getDefault().getCountry()));
	}
	
}
