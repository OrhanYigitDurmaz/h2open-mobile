import requests


def provision_extension(api_url: str, api_key: str, extension: str, secret: str, user: str) -> None:
	response = requests.post(
		f"{api_url.rstrip('/')}/extensions",
		json={"extension": extension, "secret": secret, "user": user},
		headers={"X-API-Key": api_key},
		timeout=10,
	)
	response.raise_for_status()
