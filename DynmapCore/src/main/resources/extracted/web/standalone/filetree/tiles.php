<?php
// Used for interacting with tiles

include('authentication.php');

switch ($_SERVER["REQUEST_METHOD"]) {
	case "POST":
	case "PUT":
		setTile();
		break;
}

function setTile()
{
	if (!authenticateUser()) {
		response(null, 401);	// unauthorized
		return;
	}

	$tile = [
		"world" => $_REQUEST["world"] ?? null,
		"map_prefix" => $_REQUEST["map_prefix"] ?? null,
		"x" => $_REQUEST["x"] ?? null,
		"y" => $_REQUEST["y"] ?? null,
		"zoom" => $_REQUEST["zoom"] ?? null,
		"hash" => $_REQUEST["hash"] ?? null,
		"file_type" => $_REQUEST["file_type"] ?? null
	];

	$file = $_FILES["file"] ?? null;

	// make sure no parameters were left null
	foreach ($tile as $k => $v) {
		if (!isset($v)) {
			response("Missing parameter: $k", 400);	// bad request
			return;
		}
	}

	if (!is_array($file)) {
		response("File upload missing", 400);
		return;
	}

	// create paths for the newly created file
	// /var/www/standalone/filetree/tiles/world/flat/0/
	$tile_file_container = sprintf("%s/tiles/%s/%s/%s/", dirname(__FILE__), $tile["world"], $tile["map_prefix"], $tile["zoom"]);
	// 460.69.png
	$tile_file_name = sprintf("%s.%s.%s", $tile["x"], $tile["y"], $tile["file_type"]);

	// create tile container if it doesn't exist
	if (!file_exists($tile_file_container)) {
		mkdir($tile_file_container, 0777, true);
	}

	// move uploaded tile file to location
	try {
		if (move_uploaded_file($file["tmp_name"], $tile_file_container . $tile_file_name)) {
			// success, write file hash
			$hash_file = fopen($tile_file_container . $tile_file_name . ".md5", "w");
			if (!$hash_file || !fwrite($hash_file, $tile["hash"])) {
				// failed to write hash, delete image file
				unlink($tile_file_container . $tile_file_name);
				throw new Exception("Failed to write tile hash");
			}
		} else {
			throw new Exception("Failed to write tile image file");
		}
	} catch (Exception $ex) {
		response($ex->getMessage(), 500);
		return;
	}

	response(null, 200);	// success
}

function response($body, $code = 200)
{
	http_response_code($code);
	echo $body;
}
