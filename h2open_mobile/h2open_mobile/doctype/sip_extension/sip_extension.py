import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import now_datetime

from h2open_mobile.h2open_mobile.asterisk import provision_extension


class SIPExtension(Document):
	def after_insert(self):
		self._provision()

	def on_update(self):
		if self.has_value_changed("sip_secret") or self.has_value_changed("extension"):
			self._provision()

	@frappe.whitelist()
	def rotate_secret(self):
		self.sip_secret = frappe.generate_hash(length=32)
		self.save()
		frappe.msgprint(_("SIP secret rotated and re-provisioned"))

	def _provision(self):
		settings = frappe.get_single("H2Open Mobile Settings")
		if not settings.asterisk_api_url:
			return
		self.db_set("provisioned_at", now_datetime(), update_modified=False)
		provision_extension(
			api_url=settings.asterisk_api_url,
			api_key=settings.get_password("asterisk_api_key"),
			extension=self.extension,
			secret=self.get_password("sip_secret"),
			user=self.user,
		)
