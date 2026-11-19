function require(name) {
  if (name !== 'any-listen') throw Error('Unsupported metadata module');
  const bridge = (action, args) => {
    const result = JSON.parse(__catalog_native(action, JSON.stringify(args)));
    if (result.error) throw Error(result.error);
    return result.value;
  };
  const serial = (input) => ArrayBuffer.isView(input) ? Array.from(input) : input;
  const binary = (data, from, to) => {
    const output = bridge('convert', [serial(data), from, to]);
    return to === 'binary' ? new Uint8Array(output) : output;
  };
  return {
    env: { version: '1.4.0' },
    logcat: { info() {}, warn() {}, error() {} },
    t(key) {
      const labels = {
        'songlist.sort.recommend': '推荐',
        'songlist.sort.hot': '最热',
        'songlist.sort.new': '最新',
        'songlist.sort.hot_collect': '热藏',
        'songlist.sort.soaring': '飙升',
      };
      return labels[key] || key;
    },
    async request(url, options = {}) {
      const response = bridge('request', [url, options]);
      if (options.needRaw) response.raw = binary(response.raw, 'base64', 'binary');
      return response;
    },
    utils: {
      dataConverter: async (data, from = 'utf-8', to = typeof data === 'string' ? 'binary' : from) =>
        binary(data, from, to),
      zlib: {
        inflate: async (data, encoding = 'binary', options = {}) => {
          const result = bridge('inflate', [serial(data), encoding, options.finishFlush || '']);
          return encoding === 'binary' ? binary(result, 'base64', 'binary') : result;
        },
      },
      iconv: { decode: async (data, charset) => bridge('decode', [serial(data), charset]) },
      crypto: {
        randomHex: () => bridge('randomHex', []),
        md5: async (data) => bridge('digest', ['MD5', serial(data)]),
        sha1: async (data) => bridge('digest', ['SHA-1', serial(data)]),
        aesEncrypt: async (mode, data, key, iv, encoding = 'base64') =>
          bridge('aes', [mode, serial(data), serial(key), serial(iv), encoding, true]),
        aesDecrypt: async (mode, data, key, iv, encoding = 'base64') =>
          bridge('aes', [mode, serial(data), serial(key), serial(iv), encoding, false]),
        rsaEncrypt: async (mode, data, key) =>
          bridge('rsa', [mode, serial(data), serial(key)]),
      },
    },
  };
}

