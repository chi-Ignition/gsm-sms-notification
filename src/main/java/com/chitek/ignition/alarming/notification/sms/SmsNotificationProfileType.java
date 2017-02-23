package com.chitek.ignition.alarming.notification.sms;

import org.apache.wicket.Application;
import org.apache.wicket.Component;
import org.apache.wicket.Page;
import org.apache.wicket.markup.repeater.RepeatingView;
import org.apache.wicket.model.IModel;

import com.chitek.ignition.alarming.notification.sms.settings.GsmSmsNotificationSettings;
import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfile;
import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfileRecord;
import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfileType;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistentRecord;
import com.inductiveautomation.ignition.gateway.localdb.persistence.RecordMeta;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.sqltags.simple.SimpleTagProvider;
import com.inductiveautomation.ignition.gateway.web.components.ConfigPanel;
import com.inductiveautomation.ignition.gateway.web.components.ConfirmationPanel;
import com.inductiveautomation.ignition.gateway.web.components.IConfirmedTask;
import com.inductiveautomation.ignition.gateway.web.components.actions.AbstractRecordInstanceAction;
import com.inductiveautomation.ignition.gateway.web.models.LenientResourceModel;
import com.inductiveautomation.ignition.gateway.web.pages.IConfigPage;

public class SmsNotificationProfileType extends AlarmNotificationProfileType {

	/** The tag provider is passed to profiles on creation */
	private transient SimpleTagProvider statusTagProvider;
	
	/**
	 * @param statusTagProvider
	 * 		The tag provider for status information
	 */
	 public SmsNotificationProfileType(SimpleTagProvider statusTagProvider)
	  {
	    super("chitek.alarming.GsmSmsNotification", "chi_sms.ProfileType.Name", "chi_sms.ProfileType.Description");
	    this.statusTagProvider = statusTagProvider;
	  }

	@Override
	public RecordMeta<? extends PersistentRecord> getSettingsRecordType() {
		return GsmSmsNotificationSettings.META;
	}

	@Override
	public AlarmNotificationProfile createNewProfile(GatewayContext context, AlarmNotificationProfileRecord profileRecord) throws Exception {
		GsmSmsNotificationSettings settings = (GsmSmsNotificationSettings)findProfileSettingsRecord(context, profileRecord);

	    if (settings == null) {
	      throw new Exception(String.format("Couldn't find settings record for profile '%s'.", profileRecord.getName() ));
	    }

	    return new SmsNotification(context, profileRecord, settings, statusTagProvider);
	}

	@Override
	public void addRecordInstanceActions(RepeatingView view, IConfigPage configPage, ConfigPanel parentPanel, PersistentRecord mainRecord, PersistentRecord subRecord) {
		view.add(new RestartAction<PersistentRecord>(view.newChildId(), configPage, parentPanel, mainRecord));
	};
	
	/**
	 * Restart the notification profile
	 *
	 * @param <R>
	 */
	private class RestartAction<R extends PersistentRecord>
		extends AbstractRecordInstanceAction<R> implements IConfirmedTask {

		public RestartAction(String id, IConfigPage configPage, ConfigPanel parentPanel, R record) {
			super(id, configPage, parentPanel, record);
		}
		
		@Override
		public IModel<String> getLabel() {
			return new LenientResourceModel("chi_sms.actions.restart.Label");
		}

		  public ConfigPanel createPanel(R record)
		  {
		    String name = RecordMeta.getRecordName(record);

		    String title = getString("chi_sms.actions.restart.ConfirmTitle", null, "Delete %s");
		    title = String.format(title, name);

		    String message = getString("chi_sms.actions.restart.ConfirmMessage", null, "Are you sure?");

		    return new ConfirmationPanel(this.configPage, this.parentPanel, this, title, message);
		  }

		@Override
		protected String getCssClass() {
			return null;
		}

		@Override
		public Class<? extends Page> execute(Component ownerComponent) throws Exception {
			GatewayContext context = (GatewayContext)Application.get();
			R record = getModelObjectAsRecord();
			// The update notofication will trigger a restart of the profile
			context.getPersistenceInterface().notifyRecordUpdated(record);
			return null;
		}
	}
}
