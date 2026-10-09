import json, os, urllib.request

def lambda_handler(event, context):
    msg = json.loads(event["Records"][0]["Sns"]["Message"])
    body = json.dumps({
        "event_type": "cloudwatch-alarm",
        "client_payload": {
            "alarm": msg.get("AlarmName"),
            "reason": msg.get("NewStateReason"),
            "time": msg.get("StateChangeTime"),
        },
    }).encode()
    req = urllib.request.Request(
        "https://api.github.com/repos/DongBrothers/FactoryFlow_BE/dispatches",
        data=body,
        headers={
            "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
            "Accept": "application/vnd.github+json",
        },
        method="POST",
    )
    urllib.request.urlopen(req)
    return {"ok": True}
