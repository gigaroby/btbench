from cStringIO import StringIO
import argparse
import os
import requests
import csv
import matplotlib.pyplot as plt
from collections import namedtuple


def parse_args():
    parser = argparse.ArgumentParser(description='Do some benchmarks.')

    parser.add_argument('--net_prefix', '-p', type=str, metavar='NET_PREFIX',
                        help='Prefix of the network (e.g. 192.168.1. )',
                        nargs='?', default='')

    parser.add_argument('device_ip_address', type=str, metavar='DEVICE',
                        help='IP address of the device '
                             'that will perform the benchmark')

    parser.add_argument('targets_ip_addresses', metavar='TARGET', type=str,
                        help='IP address of target devices', nargs='+')

    parser.add_argument('devices service port', type=int, metavar='PORT',
                        nargs='?', help='Port of the HTTP server in devices',
                        default=38080)

    return parser.parse_args()


def get_report(d_addr, t_mac):
    url = 'http://{0}:38080/throughput?target={1}'.format(d_addr, t_mac)

    try:
        csv_file = requests.get(url).content
    except requests.ConnectionError:
        return []

    os.makedirs('csv')
    with open('csv/throughput-{0}-{1}'.format(d_addr, t_mac), 'w') as f:
        f.write(csv_file)

    reader = csv.DictReader(StringIO(csv_file))
    return list(reader)


def hist(y, labels):
    x = range(len(y))
    width = 1 / 1.2

    plt.ylabel('Throughput (kbits / s)')
    plt.xticks(map(lambda item: item + width / 2.0, x), labels)
    plt.show()


def avg(l):
    if len(l) == 0:
        return None
    return sum(l) / len(l)


def main():
    args = parse_args()

    mac_url = lambda s_ip: 'http://{0}:38080/mac'.format(s_ip)

    d_addr = args.net_prefix + args.device_ip_address

    targets = []

    print 'Collecting info about targets'
    for t_addr in args.targets_ip_addresses:
        t_addr = args.net_prefix + t_addr
        print t_addr
        t_name, t_mac = requests.get(mac_url(t_addr)).content.split('\n')

        targets.append(dict(name=t_name, mac=t_mac, ip=t_addr))
    print targets

    print 'Running benchmarks:'
    for t in targets:
        t['throughput'] = None
        while t['throughput'] is None:
            print t['name']
            report = get_report(d_addr, t['mac'])
            print report

            t['throughput'] = avg(map(
                lambda x:
                ((float(x['bytes']) * 8) / 10 ** 3) /  # kBits
                (float(x['nanotime']) / 10 ** 9),      # / s
                report))

            if t['throughput'] is None:
                print 'FAILED. Retrying'

    print 'Plotting'
    hist(
        [t['throughput'] for t in targets],
        [t['name'] for t in targets]
    )


if __name__ == '__main__':
    main()