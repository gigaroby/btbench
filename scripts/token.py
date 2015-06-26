import itertools
import os
import urllib
import requests
import time

DEVICES = [
    {'name': 'n4', 'ip': '192.168.1.100', 'mac': '40:B0:FA:5F:26:8A'},
    {'name': 'n5', 'ip': '192.168.1.107', 'mac': 'BC:F5:AC:5C:50:87'},
    {'name': 'mi', 'ip': '192.168.1.102', 'mac': '74:51:BA:46:90:A2'},
    # {'name': 'opo', 'ip': '10.0.1.116', 'mac': 'C0:EE:FB:34:B6:CC'},
    {'name': 'n7', 'ip': '192.168.1.109', 'mac': '50:46:5D:CC:65:4E'}
]


def gen_url(ds, payload_length=128, rounds=5):
    master = ds[0]
    devices = ds[1:]

    m_ip = [d['ip'] for d in DEVICES if d['name'] == master][0]
    url = 'http://{0}:38080/tokres'.format(m_ip) + \
          urllib.urlencode({
              'devices': devices,
              'payloadLength': payload_length,
              'rounds': rounds
          }, doseq=1)
    return url


def main():
    payload_lengths = [256, 512, 1024, 2048, 4096]
    permutations = list(itertools.permutations(DEVICES))
    num_succesful, num_failed = 0, 0

    i = 0
    for pl in payload_lengths:
        for ds in permutations:
            i += 1
            master = ds[0]
            devices = ds[1:]

            print '*** TEST {0} of {1} ***'.format(i, len(permutations) * len(payload_lengths))
            print 'devices:', ', '.join([d['name'] for d in ds])
            print 'master:', master['name']
            print 'payload length:', pl
            print 'Running...'

            uuid = launch_token(master['ip'], [d['mac'] for d in devices])
            print 'uuid:', uuid
            started = time.time()
            fname = get_fname(ds, pl)

            while True:
                time.sleep(3)
                try:
                    results = get_results(master['ip'],  uuid)
                    save_results(results, fname)

                    if len(results.split('\n')) > len(DEVICES) * 5:  # 5 is num_rounds
                        num_succesful += 1
                        print 'Succesful!'
                        print 'Results saved as:', fname
                        print
                        time.sleep(10)
                        break
                except requests.ConnectionError:
                    pass
                finally:
                    if time.time() - started > 5 * len(DEVICES) * 5:
                        num_failed += 1
                        print 'Failed'
                        print
                        time.sleep(60)
                        break

    print '=================='
    print 'Finished!'
    print 'total tests:', num_failed + num_succesful
    print 'succesful:', num_succesful
    print 'failed:', num_failed


def get_fname(devices, payload_length):
    dirname = '_'.join([d['name'].replace('/', '_').replace(' ', '') for d in devices])
    dirname = os.path.join('token_results', dirname)
    if not os.path.exists(dirname):
        os.mkdir(dirname)

    idx = 1
    while True:
        fname = os.path.join(dirname, 'token_{0}_{1}.csv'.format(payload_length, idx))
        if not os.path.exists(fname):
            return fname
        idx += 1


def save_results(results, fname):
    with open(fname, 'w') as f:
        f.write(results)


def get_results(master_ip, uuid):
    for _ in xrange(3):
        try:
            results = requests.get('http://{0}:38080/tokres'.format(master_ip), params=dict(uuid=uuid)).content
            return results.strip()
        except requests.ConnectionError:
            pass
    os.system("say cagami")
    raw_input("A qualcuno e' andato in figa il wifi. Premi invio per continuare...")
    return get_results(master_ip, uuid)


def launch_token(master_ip, devices_addr, payload_length=512, rounds=5):
    for _ in xrange(3):
        try:
            uuid = requests.get('http://{0}:38080/token'.format(master_ip),
                                params=dict(devices=devices_addr,
                                            payloadLength=payload_length,
                                            rounds=rounds)).content.strip()
            return uuid
        except requests.ConnectionError:
            pass
    os.system("say cagami")
    raw_input("A qualcuno e' andato in figa il wifi. Premi invio per continuare...")
    return launch_token(master_ip, devices_addr, payload_length, rounds)

if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        pass
