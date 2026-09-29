import argparse
import json
import sys
from pathlib import Path
from .runtime import PhoneTools, compact


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description='BBUI phone tools; no embedded decision model')
    parser.add_argument('tool', choices=['observe', 'act', 'stop', 'resume', 'devices'])
    parser.add_argument('--file', type=Path, help='UTF-8 JSON action file')
    args = parser.parse_args()
    try:
        if args.tool == 'devices':
            import adbutils
            result = [{'serial': d.serial} for d in adbutils.adb.device_list()]
        else:
            phone = PhoneTools()
            if args.tool == 'observe':
                result = compact(phone.observe())
            elif args.tool == 'act':
                action = json.loads(args.file.read_text('utf-8-sig') if args.file else sys.stdin.read())
                result = phone.act(action)
            else:
                result = getattr(phone, args.tool)()
        print(json.dumps(result, ensure_ascii=False))
    except Exception as e:
        print(json.dumps({'error': type(e).__name__, 'message': str(e)}, ensure_ascii=False))
        sys.exit(1)


if __name__ == '__main__':
    main()
