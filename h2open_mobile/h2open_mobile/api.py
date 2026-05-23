import frappe
from frappe import _
from frappe.utils import now_datetime


@frappe.whitelist()
def get_sip_credentials():
	user = frappe.session.user
	ext = frappe.db.get_value("SIP Extension", {"user": user}, ["extension", "name"], as_dict=True)
	if not ext:
		frappe.throw(_("No SIP extension assigned to your account"), frappe.DoesNotExistError)
	doc = frappe.get_doc("SIP Extension", ext.name)
	return {
		"extension": doc.extension,
		"secret": doc.get_password("sip_secret"),
		"user": user,
	}


@frappe.whitelist()
def register_device(device_id: str, fcm_token: str, platform: str = "Android"):
	user = frappe.session.user
	existing = frappe.db.get_value("Mobile Device", {"device_id": device_id}, "name")
	if existing:
		doc = frappe.get_doc("Mobile Device", existing)
		doc.fcm_token = fcm_token
		doc.platform = platform
		doc.last_seen = now_datetime()
		doc.is_active = 1
		doc.save(ignore_permissions=True)
	else:
		doc = frappe.get_doc(
			{
				"doctype": "Mobile Device",
				"user": user,
				"device_id": device_id,
				"fcm_token": fcm_token,
				"platform": platform,
				"last_seen": now_datetime(),
				"is_active": 1,
			}
		)
		doc.insert(ignore_permissions=True)
	return {"status": "ok", "device": doc.name}


@frappe.whitelist()
def update_fcm_token(device_id: str, fcm_token: str):
	name = frappe.db.get_value("Mobile Device", {"device_id": device_id}, "name")
	if not name:
		frappe.throw(_("Device not found"), frappe.DoesNotExistError)
	frappe.db.set_value(
		"Mobile Device",
		name,
		{"fcm_token": fcm_token, "last_seen": now_datetime()},
		update_modified=False,
	)
	return {"status": "ok"}


@frappe.whitelist()
def logout_all_devices():
	user = frappe.session.user
	frappe.db.set_value(
		"Mobile Device",
		{"user": user},
		"is_active",
		0,
		update_modified=False,
	)
	# Regenerate API key to invalidate all tokens
	user_doc = frappe.get_doc("User", user)
	user_doc.api_key = ""
	user_doc.api_secret = ""
	user_doc.save(ignore_permissions=True)
	frappe.generate_keys(user)
	return {"status": "ok"}
