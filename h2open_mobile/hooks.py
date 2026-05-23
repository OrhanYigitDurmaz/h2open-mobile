app_name = "h2open_mobile"
app_title = "H2Open Mobile"
app_publisher = "H2Open"
app_description = "Mobile integration for H2Open"
app_email = "orhan@h2open.com"
app_license = "mit"

required_apps = ["frappe"]

# Apps
# ------------------

# required_apps = []

# Each item in the list will be shown as an app in the apps page
# add_to_apps_screen = [
# 	{
# 		"name": "h2open_mobile",
# 		"logo": "/assets/h2open_mobile/logo.png",
# 		"title": "H2Open Mobile",
# 		"route": "/h2open_mobile",
# 		"has_permission": "h2open_mobile.api.permission.has_app_permission"
# 	}
# ]

# Includes in <head>
# ------------------

# include js, css files in header of desk.html
# app_include_css = "/assets/h2open_mobile/css/h2open_mobile.css"
# app_include_js = "/assets/h2open_mobile/js/h2open_mobile.js"

# include js, css files in header of web template
# web_include_css = "/assets/h2open_mobile/css/h2open_mobile.css"
# web_include_js = "/assets/h2open_mobile/js/h2open_mobile.js"

# include custom scss in every website theme (without file extension ".scss")
# website_theme_scss = "h2open_mobile/public/scss/website"

# include js, css files in header of web form
# webform_include_js = {"doctype": "public/js/doctype.js"}
# webform_include_css = {"doctype": "public/css/doctype.css"}

# include js in page
# page_js = {"page" : "public/js/file.js"}

# include js in doctype views
# doctype_js = {"doctype" : "public/js/doctype.js"}
# doctype_list_js = {"doctype" : "public/js/doctype_list.js"}
# doctype_tree_js = {"doctype" : "public/js/doctype_tree.js"}
# doctype_calendar_js = {"doctype" : "public/js/doctype_calendar.js"}

# Svg Icons
# ------------------
# include app icons in desk
# app_include_icons = "h2open_mobile/public/icons.svg"

# Home Pages
# ----------

# application home page (will override Website Settings)
# home_page = "login"

# website user home page (by Role)
# role_home_page = {
# 	"Role": "home_page"
# }

# Generators
# ----------

# automatically create page for each record of this doctype
# website_generators = ["Web Page"]

# automatically load and sync documents of this doctype from downstream apps
# importable_doctypes = [doctype_1]

# Jinja
# ----------

# add methods and filters to jinja environment
# jinja = {
# 	"methods": "h2open_mobile.utils.jinja_methods",
# 	"filters": "h2open_mobile.utils.jinja_filters"
# }

# Installation
# ------------

# before_install = "h2open_mobile.install.before_install"
# after_install = "h2open_mobile.install.after_install"

# Uninstallation
# ------------

# before_uninstall = "h2open_mobile.uninstall.before_uninstall"
# after_uninstall = "h2open_mobile.uninstall.after_uninstall"

# Integration Setup
# ------------------
# To set up dependencies/integrations with other apps
# Name of the app being installed is passed as an argument

# before_app_install = "h2open_mobile.utils.before_app_install"
# after_app_install = "h2open_mobile.utils.after_app_install"

# Integration Cleanup
# -------------------
# To clean up dependencies/integrations with other apps
# Name of the app being uninstalled is passed as an argument

# before_app_uninstall = "h2open_mobile.utils.before_app_uninstall"
# after_app_uninstall = "h2open_mobile.utils.after_app_uninstall"

# Build
# ------------------
# To hook into the build process

# after_build = "h2open_mobile.build.after_build"

# Desk Notifications
# ------------------
# See frappe.core.notifications.get_notification_config

# notification_config = "h2open_mobile.notifications.get_notification_config"

# Permissions
# -----------
# Permissions evaluated in scripted ways

# permission_query_conditions = {
# 	"Event": "frappe.desk.doctype.event.event.get_permission_query_conditions",
# }
#
# has_permission = {
# 	"Event": "frappe.desk.doctype.event.event.has_permission",
# }

# Document Events
# ---------------
# Hook on document methods and events

# doc_events = {
# 	"*": {
# 		"on_update": "method",
# 		"on_cancel": "method",
# 		"on_trash": "method"
# 	}
# }

# Scheduled Tasks
# ---------------

# scheduler_events = {
# 	"all": [
# 		"h2open_mobile.tasks.all"
# 	],
# 	"daily": [
# 		"h2open_mobile.tasks.daily"
# 	],
# 	"hourly": [
# 		"h2open_mobile.tasks.hourly"
# 	],
# 	"weekly": [
# 		"h2open_mobile.tasks.weekly"
# 	],
# 	"monthly": [
# 		"h2open_mobile.tasks.monthly"
# 	],
# }

# Testing
# -------

# before_tests = "h2open_mobile.install.before_tests"

# Extend DocType Class
# ------------------------------
#
# Specify custom mixins to extend the standard doctype controller.
# extend_doctype_class = {
# 	"Task": "h2open_mobile.custom.task.CustomTaskMixin"
# }

# Overriding Methods
# ------------------------------
#
# override_whitelisted_methods = {
# 	"frappe.desk.doctype.event.event.get_events": "h2open_mobile.event.get_events"
# }
#
# each overriding function accepts a `data` argument;
# generated from the base implementation of the doctype dashboard,
# along with any modifications made in other Frappe apps
# override_doctype_dashboards = {
# 	"Task": "h2open_mobile.task.get_dashboard_data"
# }

# exempt linked doctypes from being automatically cancelled
#
# auto_cancel_exempted_doctypes = ["Auto Repeat"]

# Ignore links to specified DocTypes when deleting documents
# -----------------------------------------------------------

# ignore_links_on_delete = ["Communication", "ToDo"]

# Request Events
# ----------------
# before_request = ["h2open_mobile.utils.before_request"]
# after_request = ["h2open_mobile.utils.after_request"]

# Job Events
# ----------
# before_job = ["h2open_mobile.utils.before_job"]
# after_job = ["h2open_mobile.utils.after_job"]

# User Data Protection
# --------------------

# user_data_fields = [
# 	{
# 		"doctype": "{doctype_1}",
# 		"filter_by": "{filter_by}",
# 		"redact_fields": ["{field_1}", "{field_2}"],
# 		"partial": 1,
# 	},
# 	{
# 		"doctype": "{doctype_2}",
# 		"filter_by": "{filter_by}",
# 		"partial": 1,
# 	},
# 	{
# 		"doctype": "{doctype_3}",
# 		"strict": False,
# 	},
# 	{
# 		"doctype": "{doctype_4}"
# 	}
# ]

# Authentication and authorization
# --------------------------------

# auth_hooks = [
# 	"h2open_mobile.auth.validate"
# ]

# Automatically update python controller files with type annotations for this app.
# export_python_type_annotations = True

# default_log_clearing_doctypes = {
# 	"Logging DocType Name": 30  # days to retain logs
# }

# Translation
# ------------
# List of apps whose translatable strings should be excluded from this app's translations.
# ignore_translatable_strings_from = []

