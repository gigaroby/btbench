from cStringIO import StringIO
import argparse
import os
import requests
import csv
import matplotlib.pyplot as plt
import sys

# todo: remove me!!
HARDCODED_DEVICES = [
    # {'name': 'Nexus 5', 'ip': '192.168.1.107', 'mac': 'BC:F5:AC:5C:50:87'},
    # {'name': 'Galaxy tab', 'ip': '192.168.1.102', 'mac': '18:22:7E:FC:83:09'},
    # {'name': 'Redmi', 'ip': '192.168.1.116', 'mac': '74:51:BA:46:90:A2'},
    # {'name': 'GalaxyS3', 'ip': '192.168.1.100', 'mac': '9C:65:B0:88:03:28'},
    # {'name': 'Nexus 7', 'ip': '192.168.1.111', 'mac': '50:46:5D:CC:65:4E'},

    # # spaziodati
    {'name': 'Nexus 5', 'ip': '10.0.1.49', 'mac': '34:FC:EF:34:30:2E'},
    {'name': 'Nexus 7', 'ip': '10.0.1.50', 'mac': '50:46:5D:CC:65:4E'},
    {'name': 'Galaxy tab', 'ip': '10.0.1.51', 'mac': '18:22:7E:FC:83:09'},
    {'name': 'Redmi', 'ip': '10.0.1.52', 'mac': '74:51:BA:46:90:A2'},
    {'name': 'HTC One M8', 'ip': '10.0.1.54', 'mac': '2C:8A:72:25:CB:4C'},
    # {'name': 'OnePlus One', 'ip': '10.0.1.55', 'mac': ''}
]


def parse_args():
    parser = argparse.ArgumentParser(description='Do some benchmarks.')

    parser.add_argument('--net_prefix', '-n', type=str, metavar='NET_PREFIX',
                        help='Prefix of the network (e.g. 192.168.1. )',
                        nargs='?', default='')

    parser.add_argument('devices_ip_addresses', metavar='DEVICE', type=str,
                        help='IP address of target devices', nargs='+')

    parser.add_argument('--port', '-p',
                        type=int, metavar='PORT',
                        help='Port of the HTTP server in devices',
                        default=38080)

    parser.add_argument('--cache', '-c', dest='cache', action='store_const',
                        const=True, default=False,
                        help='Port of the HTTP server in devices')

    parser.add_argument('--throughput', '-t', dest='throughput',
                        action='store_const', const=True, default=False,
                        help='Throughput test.')

    parser.add_argument('--messages', '-m', dest='messages',
                        action='store_const', const=True, default=False,
                        help='Messages per second test.')

    args = parser.parse_args()
    if not args.throughput and not args.messages:
        print 'ERROR. You must specify at least an action!'
        print

        parser.print_help()

    return args


def mk_groups(data):
    try:
        newdata = data.items()
    except AttributeError:
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


def label_group_bar(fig, ax, data, ylabel):
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

    plt.ylabel(ylabel)

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


def label_group_bar_with_err(fig, ax, data, ylabel):
    groups = mk_groups(data)
    xy = groups.pop()
    x, y = zip(*xy)
    ly = len(y)
    xticks = range(1, ly + 1)

    # ax.bar(xticks,
    #        map(lambda _y: _y[0], y),
    #        map(lambda _y: _y[1:], y),
    #        align='center')
    ax.errorbar(xticks,
           map(lambda _y: _y[0], y),
           yerr=[map(lambda _y: _y[1], y), map(lambda _y: _y[2], y)],
           fmt='o')
    ax.set_xticks(xticks)
    ax.set_xticklabels(x, rotation='vertical')
    ax.set_xlim(.5, ly + .5)
    ax.yaxis.grid(True)

    plt.ylabel(ylabel)

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


def double_bar_plot(data1, data2, ylabel, xlabel, xlabels, data1_label, data2_label):
    assert len(data1) == len(data2), 'data1 and data2 must have same length'

    fig, ax = plt.subplots()
    ind = range(1, len(data1) + 1)
    width = 0.35

    rects1 = ax.bar(ind, data1, width, color='b')
    rects2 = ax.bar(map(lambda x: x+width, ind), data2, width, color='r')
    ax.set_ylabel(ylabel)
    ax.set_xlabel(xlabel)
    ax.set_xticks(map(lambda x: x+width, ind))

    ax.set_xticklabels(xlabels)
    ax.legend((rects1[0], rects2[0]), (data1_label, data2_label))
    fig.subplots_adjust(bottom=0.3)
    return fig


def simple_hist(data, xlabel, xlabels, ylabel):
    fig, ax = plt.subplots()
    width = 0.8
    left_margins = [i + (1 - width)/2 for i in range(len(data))]

    rects1 = ax.bar(left_margins, data, width, color='b')
    ax.set_ylabel(ylabel)
    ax.set_xticks([x + width / 2 for x in left_margins])
    ax.set_xlabel(xlabel)
    ax.set_xticklabels(xlabels)
    fig.subplots_adjust(bottom=0.3)
    return fig


def hist_with_line(xlabel, ylabel_sx, ylabel_dx, data_bars, data_line, xlabels):
    assert len(data_line) == len(data_bars), "datasets mush be the same length"

    fig, ax = plt.subplots()
    width = 0.7
    left_margins = [i + (1 - width)/2 for i in range(len(data_bars))]

    rects1 = ax.bar(left_margins, data_bars, width, color='b')
    ax.set_ylabel(ylabel_sx, color='b')
    ax.set_xticks([x + width / 2 for x in left_margins])
    ax.set_xlabel(xlabel)
    ax.set_xticklabels(xlabels)
    ax.yaxis.grid(True)
    ax.xaxis.grid(True)

    for tl in ax.get_yticklabels():
        tl.set_color('b')

    ax2 = ax.twinx()
    ax2.set_ylabel(ylabel_dx, color='r')
    ax2.plot(
        [m + width / 2 for m in left_margins],
        data_line,
        color='r',
        marker='o'
    )
    ax2.margins(0.1, 0.1)
    ax2.set_ylim([0, None])

    fig.subplots_adjust(bottom=0.3)
    for tl in ax2.get_yticklabels():
        tl.set_color('r')
    return fig


def get_throughput_report(d_addr, t_mac, cached=False):
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


def avg(l):
    if len(l) == 0:
        return None
    return sum(l) / len(l)


def trymin(l):
    if len(l) == 0:
        return None
    return min(l)


def trymax(l):
    if len(l) == 0:
        return None
    return max(l)


def bench_throughput(devices, dest_path, cache=False):
    print 'Running throughput benchmark:'
    results = {}
    for receiver in devices:
        for sender in devices:
            if receiver == sender:
                continue

            r_name = receiver['name']
            s_name = sender['name']

            if r_name not in results:
                results[r_name] = {}

            results[r_name][s_name] = (None,)
            while None in results[r_name][s_name]:
                print r_name, ' <- ', sender['name']

                # get_throughput_report(receiver['ip'], sender['mac'], cache)  # warmup
                report = get_throughput_report(receiver['ip'], sender['mac'], cache)

                _res = map(
                    lambda x:
                    ((float(x['bytes']) * 8) / 10 ** 3) /  # kBits
                    (float(x['nanotime']) / 10 ** 9),      # / s
                    report)

                results[r_name][s_name] = (
                    avg(_res),
                    trymin(_res),
                    trymax(_res)
                )

                if None in results[r_name][s_name]:
                    print 'FAILED. Retrying'

            print 'DONE.'

    print 'Plotting throughput...'
    fig = plt.figure()
    ax = fig.add_subplot(1, 1, 1)
    label_group_bar_with_err(fig, ax, results, 'Throughput (kbits / s)')
    fig.subplots_adjust(bottom=0.3)
    fig.savefig(dest_path)


def get_messages_per_sec_throughput(devices, dest_path, cache=False):
    N_MESSAGES = 40

    assert len(devices) >= 2, 'Need at least two devices'

    master = devices[0]
    others = devices[1:]
    results = {}

    for i in range(1, len(others) + 1):
        targets = others[:i]
        print targets

        url = 'http://{0}:{1}/messages'.format(master['ip'], 38080)
        f_name = lambda tt: 'csv/messages-{0}-{1}-{2}.csv'.format(
            master['name'].replace(' ', '_'),
            N_MESSAGES,
            '-'.join([_d['name'].replace(' ', '_') for _d in tt])
        )

        if cache and os.path.exists(f_name(targets)):
            print f_name(targets)
            res_raw = open(f_name(targets), 'r')
        else:
            print url
            print targets

            vars = dict(
                target=[d['mac'] for d in targets],
                messages=N_MESSAGES
            )

            r = requests.get(url, params=vars)
            print r.url

            with open(f_name(targets), 'w') as f:
                f.write(r.content)

            res_raw = StringIO(r.content)

        res = list(csv.DictReader(res_raw))

        # from, to, message_size, started, received, finished
        # % msg persi, RTT (finished-started), conn cost approx (rec - started)
        if i not in results:
            results[i] = {}
        print 'first: ', min([int(t['finished']) for t in res]), ' , second: ', max([int(t['finished']) for t in res])
        results[i]['timespan'] = ((max([int(t['finished']) for t in res]) - min([int(t['finished']) for t in res])) / 1000)
        results[i]['rtts'] = [int(t['finished']) - int(t['started']) for t in res]
        results[i]['conn_cost_approx'] = [int(t['received']) - int(t['started']) for t in res]
        results[i]['received_msgs_rate'] = (len(res) * 100) / (N_MESSAGES * len(targets))
        results[i]['received_msgs'] = len(res) / len(targets)

    # print results


    # fig = double_bar_plot(
    #     [avg(results[i]['rtts']) for i in range(1, len(others) + 1)],
    #     [avg(results[i]['conn_cost_approx']) for i in range(1, len(others) + 1)],
    #     'Round trip time (ms)',
    #     'Number of devices',
    #     range(1, len(others) + 1),
    #     'Average message round trip time',
    #     'Cost of connection establishment (approx.)'
    # )

    fig = hist_with_line(
        "Number of devices (excluding master)",
        "Round trip time (ms)",
        "Number of messages per second per device",
        [avg(results[i]['rtts']) for i in range(1, len(others) + 1)],
        [results[i]['received_msgs'] / results[i]['timespan'] for i in range(1, len(others) + 1)],
        range(1, len(others) + 1)
    )

    fig.savefig('asd.png')


def main():
    args = parse_args()

    mac_url = lambda s_ip: 'http://{0}:{1}/mac'.format(s_ip, args.port)

    devices = []

    print 'Collecting info about devices'
    for t_addr in args.devices_ip_addresses:
        t_addr = args.net_prefix + t_addr

        sys.stdout.write(t_addr + '... ')
        sys.stdout.flush()

        if args.cache:
            d = [x for x in HARDCODED_DEVICES if x['ip'] == t_addr]

            if any(d):
                device = d[0]
                sys.stdout.write('Hello, {0}!\n'.format(device['name']))
                sys.stdout.flush()
                devices.append(device)
                continue

        t_name, t_mac = requests.get(mac_url(t_addr)).content.split('\n')
        sys.stdout.write('Hello, {0}!\n'.format(t_name))
        sys.stdout.flush()
        devices.append(dict(name=t_name, mac=t_mac, ip=t_addr))

    print devices

    if args.throughput:
        bench_throughput(devices, 'throughput.png', args.cache)

    if args.messages:
        get_messages_per_sec_throughput(devices, 'messages.png', args.cache)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        pass
