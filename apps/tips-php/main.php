<?php
function init() { return ['bill' => '', 'pct' => 15, 'people' => 1, 'round' => false]; }

function update($s, $e) {
    $e['id'] = $e['id'] ?? '';
    $s['bill'] = $e['inputs']['bill'] ?? $s['bill'];
    if (str_starts_with($e['id'] ?? '', 'pct:')) $s['pct'] = (int)substr($e['id'], 4);
    if ($e['id'] === 'more') $s['people']++;
    if ($e['id'] === 'less') $s['people'] = max(1, $s['people'] - 1);
    if ($e['id'] === 'round') $s['round'] = (bool)$e['value'];
    return $s;
}

function view($s) {
    $bill = (float)$s['bill'];
    $tip = $bill * $s['pct'] / 100;
    $each = ($bill + $tip) / $s['people'];
    if ($s['round']) $each = ceil($each);
    $chips = [];
    foreach ([10, 15, 18, 20, 25] as $p) {
        $on = $p === $s['pct'];
        $chips[] = ['type' => 'button', 'id' => "pct:$p", 'text' => "$p%", 'flex' => 1, 'bg' => $on ? '#059669' : '#D1FAE5', 'color' => $on ? '#FFFFFF' : '#065F46'];
    }
    return ['title' => 'Tip Split', 'accent' => '#059669', 'bg' => '#ECFDF5', 'body' => ['type' => 'column', 'padding' => 16, 'gap' => 14, 'children' => [
        ['type' => 'input', 'id' => 'bill', 'hint' => 'Bill amount', 'kind' => 'number', 'value' => $s['bill']],
        ['type' => 'button', 'id' => 'calc', 'text' => 'Update', 'bg' => '#059669'],
        ['type' => 'row', 'gap' => 6, 'children' => $chips],
        ['type' => 'row', 'gap' => 12, 'children' => [
            ['type' => 'text', 'text' => 'People', 'flex' => 1, 'size' => 18],
            ['type' => 'button', 'id' => 'less', 'text' => '−', 'width' => 52, 'bg' => '#059669'],
            ['type' => 'text', 'text' => (string)$s['people'], 'size' => 22, 'bold' => true],
            ['type' => 'button', 'id' => 'more', 'text' => '+', 'width' => 52, 'bg' => '#059669'],
        ]],
        ['type' => 'switch', 'id' => 'round', 'text' => 'Round up per person', 'value' => $s['round']],
        ['type' => 'card', 'children' => [
            ['type' => 'text', 'text' => 'Tip: ' . number_format($tip, 2), 'color' => '#6B7280'],
            ['type' => 'text', 'text' => number_format($each, 2), 'size' => 44, 'bold' => true, 'color' => '#065F46'],
            ['type' => 'text', 'text' => 'per person', 'color' => '#6B7280'],
        ]],
    ]]];
}
