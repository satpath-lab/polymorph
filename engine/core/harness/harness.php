<?php
$__VOLT_DIR = getenv('VOLT_DIR'); $__VOLT_MAIN = getenv('VOLT_MAIN');
chdir($__VOLT_DIR);
set_include_path($__VOLT_DIR . PATH_SEPARATOR . $__VOLT_DIR . '/lib');
ob_start(function ($b) { if ($b !== '') foreach (explode("\n", rtrim($b, "\n")) as $l) __volt_host(json_encode(['op' => 'log', 'text' => $l])); return ''; }, 1);
set_error_handler(function ($no, $str, $file, $line) { throw new ErrorException($str, 0, $no, $file, $line); });

$GLOBALS['__volt_cbs'] = []; $GLOBALS['__volt_cbn'] = 0;
function __volt_wrap($v) {
    if ($v instanceof Closure || (is_object($v) && method_exists($v, '__invoke'))) { $GLOBALS['__volt_cbs'][++$GLOBALS['__volt_cbn']] = $v; return ['__volt_cb' => $GLOBALS['__volt_cbn']]; }
    if (is_array($v)) { foreach ($v as $k => $x) $v[$k] = __volt_wrap($x); }
    return $v;
}

final class AndroidTarget {
    public function __construct(private string $n) {}
    public function __call($m, $a) { return Android::hostcall($this->n, $m, array_values($a)); }
    public function __invoke(string $method = '', array $args = []) { return Android::hostcall($this->n, $method, (object)$args); }
}

final class Android {
    public static function hostcall(string $name, string $method, $args) {
        $r = json_decode(__volt_host(json_encode(['op' => 'call', 'plugin' => $name, 'method' => $method, 'args' => __volt_wrap($args)], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)), true);
        if (is_array($r) && isset($r['error'])) throw new RuntimeException($r['error']);
        return is_array($r) ? ($r['value'] ?? null) : $r;
    }
    public static function call(string $name, string $method = '', array $args = []) { return self::hostcall($name, $method, (object)$args); }
    public static function emit(string $name, $data = null) { __volt_host(json_encode(['op' => 'emit', 'name' => $name, 'data' => __volt_wrap($data)], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)); }
    public static function __callStatic($name, $a) { return $a ? self::hostcall($name, $a[0] ?? '', (object)($a[1] ?? [])) : new AndroidTarget($name); }
}
class_alias('Android', 'Native');
function android() { static $p; return $p ??= new class { public function __call($n, $a) { return $a ? Android::hostcall($n, $a[0] ?? '', (object)($a[1] ?? [])) : new AndroidTarget($n); } public function __get($n) { return new AndroidTarget($n); } }; }

$__volt_err = null;
try { (function () use ($__VOLT_MAIN) { require $__VOLT_MAIN; })(); } catch (Throwable $e) { $__volt_err = $e; }

function __volt_json($v) {
    $s = json_encode(__volt_wrap($v), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PARTIAL_OUTPUT_ON_ERROR | JSON_PRESERVE_ZERO_FRACTION);
    return $s === false ? '{"error":"result is not JSON-encodable"}' : $s;
}

function __volt($fn, $arg) {
    global $__volt_err;
    try {
        if ($__volt_err) throw $__volt_err;
        $a = json_decode($arg, true);
        if ($fn === '__cb') {
            $f = $GLOBALS['__volt_cbs'][$a[0]] ?? null;
            if (!$f) throw new RuntimeException('callback was released');
            $res = ['value' => $f(...array_slice($a, 1))];
            @ob_flush();
            return __volt_json($res);
        }
        if ($fn === '__release') { unset($GLOBALS['__volt_cbs'][$a[0]]); return '{"value":true}'; }
        if ($fn === 'step') {
            $st = $a['state'] ?? null;
            if ($st === null) $st = function_exists('init') ? init() : [];
            if (($a['event'] ?? null) !== null && function_exists('update')) { $r = update($st, $a['event']); if ($r !== null) $st = $r; }
            $res = ['state' => $st, 'ui' => function_exists('view') ? view($st) : null];
        } else {
            if (!function_exists($fn)) throw new RuntimeException("no function $fn");
            $res = ['value' => $fn(...(is_array($a) && array_is_list($a) ? $a : [$a]))];
        }
    } catch (Throwable $e) {
        $res = ['error' => get_class($e) . ': ' . $e->getMessage() . ' (' . basename($e->getFile()) . ':' . $e->getLine() . ')'];
    }
    @ob_flush();
    return __volt_json($res);
}
