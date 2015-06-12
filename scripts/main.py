from cStringIO import StringIO
import argparse
import os
import requests
import csv
import matplotlib.pyplot as plt

def parse_args():
    parser = argparse.ArgumentParser(description='Do some benchmarks.')

    parser.add_argument('--net_prefix', '-n', type=str, metavar='NET_PREFIX',
                        help='Prefix of the network (e.g. 192.168.1. )',
                        nargs='?', default='')

    # parser.add_argument('device_ip_address', type=str, metavar='DEVICE',
    #                     help='IP address of the device '
    #                          'that will perform the benchmark')

    parser.add_argument('devices_ip_addresses', metavar='DEVICE', type=str,
                        help='IP address of target devices', nargs='+')

    parser.add_argument('--port', '-p',
                        type=int, metavar='PORT',
                        help='Port of the HTTP server in devices',
                        default=38080)

    parser.add_argument('--cache', '-c', dest='cache', action='store_const',
                        const=True, default=False,
                        help='Port of the HTTP server in devices')

    return parser.parse_args()




def mk_groups(data):
    try:
        newdata = data.items()
    except:
        return

    thisgroup = []
    groups = []
    for key, value in newdata:
        newgroups = mk_groups(value)
        if newgroups is None:
            thisgroup.append((key, value))
        else:
            thisgroup.append((key, len(newgroups[-1])))
            if groups:
                groups = [g + n for n, g in zip(newgroups, groups)]
            else:
                groups = newgroups
    return [thisgroup] + groups


def add_line(ax, xpos, ypos):
    line = plt.Line2D([xpos, xpos], [ypos + .1, ypos],
                      transform=ax.transAxes, color='black')
    line.set_clip_on(False)
    ax.add_line(line)


def label_group_bar(fig, ax, data):
    groups = mk_groups(data)
    xy = groups.pop()
    x, y = zip(*xy)
    ly = len(y)
    xticks = range(1, ly + 1)

    ax.bar(xticks, y, align='center')
    ax.set_xticks(xticks)
    ax.set_xticklabels(x, rotation='vertical')
    ax.set_xlim(.5, ly + .5)
    ax.yaxis.grid(True)

    plt.ylabel('Throughput (kbits / s)')

    scale = 1. / ly
    for pos in xrange(ly + 1):
        add_line(ax, pos * scale, -.1)
    ypos = 1.02
    while groups:
        group = groups.pop()
        pos = 0
        for label, rpos in group:
            lxpos = (pos + .5 * rpos) * scale
            ax.text(lxpos, ypos, label, ha='center', transform=ax.transAxes)
            add_line(ax, pos * scale, 1)
            pos += rpos
        add_line(ax, pos * scale, 1)
        ypos -= .1

# if __name__ == '__main__':
#     fig = plt.figure()
#     ax = fig.add_subplot(1,1,1)
#     label_group_bar(ax, data)
#     fig.subplots_adjust(bottom=0.3)
#     fig.savefig('label_group_bar_example.png')




def get_report(d_addr, t_mac, cached=False):
    path = 'csv/throughput-{0}-{1}'.format(d_addr, t_mac)
    if cached:
        if os.path.exists(path):
            return csv.DictReader(open(path, 'r'))

    url = 'http://{0}:38080/throughput?target={1}'.format(d_addr, t_mac)

    try:
        csv_file = requests.get(url).content
    except requests.ConnectionError:
        return []

    with open(path, 'w') as f:
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

    devices = []

    print 'Collecting info about devices'
    for t_addr in args.devices_ip_addresses:
        t_addr = args.net_prefix + t_addr
        print t_addr
        # if t_addr.endswith('115'):
        #     time.sleep(2)
        #     for c in "PORCA TROIA GALAXY TAB DI MERDA SE NON TI SVEGLI!!!\n*FANCULO, FACCIO A MENO\n":
        #         if c == '*':
        #             time.sleep(1)
        #             continue
        #         time.sleep(0.01)
        #         sys.stdout.write(c)
        #         sys.stdout.flush()
        #     continue

        t_name, t_mac = requests.get(mac_url(t_addr)).content.split('\n')
        devices.append(dict(name=t_name, mac=t_mac, ip=t_addr))

    print devices

    print 'Running benchmarks:'
    results = {}
    for receiver in devices:
        for sender in devices:
            if receiver == sender:
                continue

            r_name = receiver['name']
            s_name = sender['name']

            if r_name not in results:
                results[r_name] = {}

            results[r_name][s_name] = None
            while results[r_name][s_name] is None:
                print r_name, ' <- ', sender['name']

                get_report(receiver['ip'], sender['mac'], args.cache)  # warmup
                report = get_report(receiver['ip'], sender['mac'], args.cache)
                print report

                results[r_name][s_name] = avg(map(
                    lambda x:
                    ((float(x['bytes']) * 8) / 10 ** 3) /  # kBits
                    (float(x['nanotime']) / 10 ** 9),      # / s
                    report))

                if results[r_name][s_name] is None:
                    print 'FAILED. Retrying'

    print 'Plotting'
    fig = plt.figure()
    ax = fig.add_subplot(1, 1, 1)
    label_group_bar(fig, ax, results)
    fig.subplots_adjust(bottom=0.3)
    fig.savefig('plot.png')


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        pass
