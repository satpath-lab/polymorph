<?php
function init() { return ['count' => 0]; }

function update($s, $e) {
    $e['id'] = $e['id'] ?? '';
    switch ($e['id']) {
        case 'inc': $s['count']++; break;
        case 'dec': $s['count']--; break;
        case 'reset': $s['count'] = 0; $s['_toast'] = 'Reset!'; break;
    }
    return $s;
}

function view($s) {
    return ['title' => 'Counter', 'accent' => '#7A86B8', 'body' => ['type' => 'column', 'padding' => 24, 'gap' => 16, 'align' => 'center', 'children' => [
        ['type' => 'text', 'text' => 'PHP ⚡', 'size' => 14, 'color' => '#7A86B8', 'bold' => true],
        ['type' => 'text', 'text' => (string)$s['count'], 'size' => 72, 'bold' => true],
        ['type' => 'row', 'gap' => 12, 'children' => [
            ['type' => 'button', 'id' => 'dec', 'text' => '−', 'width' => 80],
            ['type' => 'button', 'id' => 'inc', 'text' => '+', 'width' => 80],
        ]],
        ['type' => 'button', 'id' => 'reset', 'text' => 'Reset', 'bg' => '#EEE', 'color' => '#333'],
    ]]];
}
