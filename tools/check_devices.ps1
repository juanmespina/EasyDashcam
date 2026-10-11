Get-PnpDevice -PresentOnly | Where-Object {
    $_.FriendlyName -match 'Android|Portable|MTP|SAMSUNG|Galaxy|Google|Pixel|Xiaomi|Redmi|POCO|OnePlus|Motorola|Moto|OPPO|Vivo|Huawei|Honor|Realme|Nothing|Sony|LG|Nokia'
} | Format-Table FriendlyName, Class, Status, InstanceId -AutoSize
